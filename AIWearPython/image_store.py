"""图片下载、特征提取、RedisJSON 入库与当前用户的图片检索。"""

import hashlib
import http.client
import ipaddress
import json
import logging
import os
import re
import socket
import ssl
import threading
import time
import warnings
from contextlib import closing
from datetime import datetime, timezone, timedelta
from io import BytesIO
from urllib.parse import parse_qsl, quote, unquote, urlsplit

import numpy as np
import redis
from PIL import Image, ImageOps, UnidentifiedImageError
from redis.backoff import NoBackoff
from redis.commands.search.query import Query
from redis.retry import Retry

from edit_image.validate_image import describe_image

import torch
from transformers import AutoModel, AutoTokenizer

logger = logging.getLogger(__name__)
IMAGE_PREFIX = "aiwear:image:"
INDEX_NAME = "idx:aiwear:images"
EMBEDDING_DIM = 512
CLIP_MODEL_PATH = os.getenv(
    "CLIP_MODEL_PATH",
    r"C:\Users\26933\.cache\modelscope\models\openai-mirror--clip-vit-base-patch16\snapshots\master",
)
TEXT_EMBEDDING_MODEL_PATH = os.getenv("TEXT_EMBEDDING_MODEL_PATH", "Qwen/Qwen3-Embedding-0.6B")
TRUSTED_OSS_HOSTS = {
    host.strip().lower() for host in os.getenv(
        "OSS_TRUSTED_HOSTS", "ai-wear666.oss-cn-beijing.aliyuncs.com"
    ).split(",") if host.strip()
}
# 与Java端保持一致，所有图片入口统一限制单张10MB。
MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_IMAGE_PIXELS = int(os.getenv("IMAGE_MAX_PIXELS", "25000000"))
_redis_client = None
# 仅防止首次并发请求重复加载本地模型，不参与图片入库的锁定或排队。
_clip_lock = threading.Lock()
_clip = None
_text_lock = threading.Lock()
_text_model = None


class ImageProcessError(Exception):
    def __init__(self, message: str, status: int):
        super().__init__(message)
        self.status = status


def get_redis_client():
    global _redis_client
    if _redis_client is None:
        pool = redis.ConnectionPool(
            host=os.getenv("REDIS_HOST", "127.0.0.1"),
            port=int(os.getenv("REDIS_PORT", "16379")),
            password=os.getenv("REDIS_PASSWORD") or None,
            db=int(os.getenv("REDIS_DB", "0")),
            socket_connect_timeout=None,
            socket_timeout=None,
            max_connections=8,
            decode_responses=True,
            protocol=2,
            retry=Retry(NoBackoff(), 0),
        )
        _redis_client = redis.Redis(connection_pool=pool)
        logger.info("图片存储 Redis 连接池已初始化，不设置连接和读取超时")
    return _redis_client


def init_image_index():
    """启动时创建图片索引；已有索引直接复用，不逐字段重复校验。"""
    client = get_redis_client()
    try:
        client.execute_command(
            "FT.CREATE", INDEX_NAME, "ON", "JSON", "PREFIX", 1, IMAGE_PREFIX,
            "LANGUAGE", "chinese", "SCHEMA",
            "$.imageId", "AS", "imageId", "TAG",
            "$.userId", "AS", "userId", "NUMERIC",
            "$.description", "AS", "description", "TEXT",
            "$.embedding", "AS", "embedding", "VECTOR", "HNSW", 6,
            "TYPE", "FLOAT32", "DIM", EMBEDDING_DIM, "DISTANCE_METRIC", "COSINE",
        )
        logger.info("图片 JSON 向量索引已创建，维度=%s", EMBEDDING_DIM)
    except redis.ResponseError as error:
        message = str(error).lower()
        if any(text in message for text in ("index already exists", "index_already_exists")):
            return
        if "unknown command" in message:
            raise ImageProcessError("Redis 需要支持 RedisJSON 和 Redis Search 向量检索", 503) from None
        raise


def validate_oss_url(oss_url: str) -> str:
    """返回不含签名的对象地址，实际下载仍使用原始签名 URL。"""
    if not isinstance(oss_url, str) or not oss_url or len(oss_url) > 8192:
        raise ImageProcessError("ossUrl 必须是有效的 OSS HTTPS 地址", 400)
    if any(char.isspace() or ord(char) < 32 for char in oss_url) or "\\" in oss_url:
        raise ImageProcessError("ossUrl 格式不合法", 400)
    try:
        url = urlsplit(oss_url)
        if (url.scheme != "https" or url.hostname not in TRUSTED_OSS_HOSTS
                or url.port not in (None, 443) or url.username is not None
                or url.password is not None or url.fragment):
            raise ValueError
        # 即使误配了白名单，也不允许直接使用 IP、localhost 或非域名主机。
        if not url.hostname or "." not in url.hostname or url.hostname.endswith("."):
            raise ValueError
        try:
            ipaddress.ip_address(url.hostname)
        except ValueError:
            pass
        else:
            raise ValueError
        if re.search(r"%(?![0-9a-fA-F]{2})", url.path):
            raise ValueError
        path = unquote(url.path, errors="strict")
        if (not path.startswith("/") or path == "/" or "\\" in path
                or any(ord(char) < 32 for char in path)
                or any(part in {".", ".."} for part in path.split("/"))):
            raise ValueError
        # 只接受 OSS 鉴权参数，禁止 x-oss-process 等改变图片内容的参数混入幂等标识。
        signature_parameters = {
            "ossaccesskeyid", "expires", "signature", "security-token",
            "x-oss-signature-version", "x-oss-credential", "x-oss-date",
            "x-oss-expires", "x-oss-signature", "x-oss-security-token",
            "x-oss-additional-headers", "x-oss-content-sha256",
        }
        if any(key.lower() not in signature_parameters for key, _ in parse_qsl(url.query, keep_blank_values=True)):
            raise ValueError
        return "https://" + url.hostname + quote(path, safe="/-_.~")
    except (ValueError, UnicodeError):
        raise ImageProcessError("ossUrl 必须指向白名单中的 OSS 对象，且仅允许签名查询参数", 400) from None


def download_image(oss_url: str) -> bytes:
    validate_oss_url(oss_url)
    url = urlsplit(oss_url)
    try:
        addresses = socket.getaddrinfo(url.hostname, 443, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
            raise ImageProcessError("OSS 域名解析到了不允许访问的地址", 400)
        # 连接已检查的公网 IP，同时保留域名的 TLS 校验和 SNI，避免二次 DNS 解析绕过校验。
        with closing(http.client.HTTPSConnection(url.hostname, timeout=None)) as connection:
            with socket.create_connection((addresses[0][4][0], 443), timeout=None) as raw_socket:
                connection.sock = ssl.create_default_context().wrap_socket(raw_socket, server_hostname=url.hostname)
                # 连接建立后持续等待图片数据，不限制读取耗时。
                connection.sock.settimeout(None)
                target = quote(url.path, safe="/%-_.~") + ("?" + url.query if url.query else "")
                connection.request("GET", target, headers={"Accept": "image/jpeg,image/png,image/webp"})
                with connection.getresponse() as response:
                    # http.client 不自动跟随重定向，所有 3xx 都视为下载失败。
                    if response.status != 200:
                        raise ImageProcessError("OSS 图片获取失败，请检查对象地址、访问权限或签名有效期", 502)
                    length = response.getheader("Content-Length")
                    if length and int(length) > MAX_IMAGE_BYTES:
                        raise ImageProcessError("图片文件不能超过10MB", 400)
                    data = bytearray()
                    while True:
                        chunk = response.read1(65536)
                        if not chunk:
                            break
                        data.extend(chunk)
                        if len(data) > MAX_IMAGE_BYTES:
                            raise ImageProcessError("图片文件不能超过10MB", 400)
                    if length and len(data) != int(length):
                        raise ImageProcessError("OSS 图片下载不完整", 502)
                    return bytes(data)
    except ImageProcessError:
        raise
    except (OSError, ValueError, http.client.HTTPException) as error:
        logger.warning("OSS 图片下载失败，异常类型=%s", type(error).__name__)
        raise ImageProcessError("OSS 图片获取失败或超时", 502) from None


def decode_image(image_bytes: bytes) -> Image.Image:
    if not image_bytes or len(image_bytes) > MAX_IMAGE_BYTES:
        raise ImageProcessError("图片为空或超过10MB", 400)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(image_bytes)) as image:
                if image.format not in {"JPEG", "PNG", "WEBP"}:
                    raise ImageProcessError("仅支持 JPEG、PNG、WEBP 图片内容", 400)
                if image.width * image.height > MAX_IMAGE_PIXELS or image.width <= 0 or image.height <= 0:
                    raise ImageProcessError("图片像素尺寸超过限制", 400)
                image.verify()
            with Image.open(BytesIO(image_bytes)) as image:
                image.load()
                return ImageOps.exif_transpose(image).convert("RGB")
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombWarning, Image.DecompressionBombError):
        raise ImageProcessError("图片内容无效、损坏或像素尺寸超过限制", 400) from None


def load_clip_model():
    """上传入库和图搜图共用同一个 CLIP 模型与预处理器。"""
    global _clip
    with _clip_lock:
        if _clip is None:
            import torch
            from transformers import CLIPModel, CLIPProcessor

            logger.info("开始从本地加载 CLIP 模型")
            device = "cuda" if torch.cuda.is_available() else "cpu"
            model = CLIPModel.from_pretrained(CLIP_MODEL_PATH, local_files_only=True)
            processor = CLIPProcessor.from_pretrained(CLIP_MODEL_PATH, local_files_only=True)
            model.to(device).eval()
            _clip = (model, processor, device)
            logger.info("CLIP 模型加载完成，device=%s", device)
    return _clip


def generate_image_embedding(image: Image.Image) -> list[float]:
    import torch

    model, processor, device = load_clip_model()
    with torch.inference_mode():
        inputs = processor(images=image, return_tensors="pt").to(device)
        features = model.get_image_features(**inputs)
        # Transformers 5.0 返回的 pooler_output 已经过 visual_projection；4.x 直接返回 Tensor。
        if not isinstance(features, torch.Tensor):
            features = features.pooler_output
        features = features.float()
        norm = features.norm(p=2, dim=-1, keepdim=True)
        if (features.shape != (1, EMBEDDING_DIM) or not torch.isfinite(features).all()
                or not torch.isfinite(norm).all() or not (norm > 0).all()):
            raise ImageProcessError("CLIP 返回了无效的图片向量", 500)
        embedding = (features / norm)[0].cpu().tolist()
    return embedding


def generate_text_embeddings(texts: list[str]) -> np.ndarray:
    global _text_model

    with _text_lock:
        if _text_model is None:
            device = "cuda" if torch.cuda.is_available() else "cpu"
            model_path = TEXT_EMBEDDING_MODEL_PATH
            if not os.path.isdir(model_path):
                from huggingface_hub import snapshot_download

                model_path = snapshot_download(model_path, local_files_only=True)
            tokenizer = AutoTokenizer.from_pretrained(
                model_path, padding_side="left", local_files_only=True
            )
            model = AutoModel.from_pretrained(
                model_path, dtype="auto", local_files_only=True
            )
            _text_model = (model.to(device).eval(), tokenizer, device)

    model, tokenizer, device = _text_model
    embeddings = []

    with torch.inference_mode():
        for i in range(0, len(texts), 32):
            inputs = tokenizer(
                texts[i:i + 32],
                padding=True,
                truncation=True,
                max_length=512,
                return_tensors="pt"
            ).to(device)

            # Qwen3 使用最后一个有效 token 的向量；左侧填充保证它位于序列末尾。
            vectors = model(**inputs, use_cache=False).last_hidden_state[:, -1].float()
            vectors = torch.nn.functional.normalize(vectors, p=2, dim=1)
            embeddings.append(vectors.cpu().numpy())

    return np.concatenate(embeddings)


def search_images(user_id: int, query: str | None = None, image: Image.Image | None = None) -> list[dict]:
    client = get_redis_client()
    images = []
    offset = 0
    while True:
        # 现有缓存没有用户图片 ID 集合，复用已有索引按用户读取，不扫描整个数据库。
        redis_query = Query(f"@userId:[{user_id} {user_id}]")
        redis_query.return_field("$", as_field="image").paging(offset, 100)
        page = client.ft(INDEX_NAME).search(redis_query)
        for document in page.docs:
            try:
                cached = json.loads(getattr(document, "image", "null"))
            except (TypeError, ValueError):
                continue
            # 再核对记录归属，过期、缺失和无地址的记录直接跳过。
            if (isinstance(cached, dict) and type(cached.get("userId")) is int
                    and cached["userId"] == user_id
                    and isinstance(cached.get("ossUrl"), str) and cached["ossUrl"].strip()):
                images.append(cached)
        offset += len(page.docs)
        if not page.docs or offset >= page.total:
            break

    if not images:
        return []

    if query is not None:
        query_embedding = generate_text_embeddings([query])[0]
        embedding_field = "descriptionEmbedding"
        similarity_threshold = 0.5
    else:
        query_embedding = np.asarray(generate_image_embedding(image), dtype=np.float32)
        embedding_field = "embedding"
        similarity_threshold = 0.7

    matches = []
    for cached in images:
        if query is not None:
            description = cached.get("description")
            if not isinstance(description, str) or not description.strip():
                continue
            if cached.get("descriptionEmbeddingModel") != TEXT_EMBEDDING_MODEL_PATH:
                continue
        try:
            embedding = np.asarray(cached.get(embedding_field), dtype=np.float32)
        except (TypeError, ValueError, OverflowError):
            continue
        if embedding.shape != query_embedding.shape or not np.isfinite(embedding).all():
            continue
        norm = np.linalg.norm(embedding)
        if not np.isfinite(norm) or norm <= 0:
            continue
        score = float(np.dot(query_embedding, embedding / norm))
        if score > similarity_threshold:
            matches.append({"filePath": cached["ossUrl"], "similarity": score})

    matches.sort(key=lambda item: item["similarity"], reverse=True)
    for match in matches:
        match["similarity"] = round(match["similarity"], 4)
    return matches


def delete_image_from_redis(oss_url: str, user_id: int) -> None:
    object_url = validate_oss_url(oss_url)
    if type(user_id) is not int or not 0 < user_id <= 9007199254740991:
        raise ImageProcessError("userId 必须是有效的正整数，且不超过 9007199254740991", 400)
    # 与入库使用同一个标识规则，删除整条JSON后，描述向量和图片向量会一起移除。
    image_id = hashlib.sha256(f"{user_id}\n{object_url}".encode("utf-8")).hexdigest()[:32]
    deleted = get_redis_client().delete(IMAGE_PREFIX + image_id)
    logger.info("图片缓存已清理，userId=%s，imageId=%s，删除条数=%s", user_id, image_id, deleted)


def save_image_to_redis(image_data: dict) -> dict:
    client = get_redis_client()
    key = IMAGE_PREFIX + image_data["imageId"]
    # 直接保存完整 JSON；NX 保证并发重复提交仍只保留同一份记录。
    if client.json().set(key, "$", image_data, nx=True):
        return image_data
    return client.json().get(key)


def process_image(oss_url: str, user_id: int) -> dict:
    object_url = validate_oss_url(oss_url)
    # Redis NUMERIC 使用双精度数；限制在精确整数范围内，确保未来 userId 过滤不会串用户。
    if type(user_id) is not int or not 0 < user_id <= 9007199254740991:
        raise ImageProcessError("userId 必须是有效的正整数，且不超过 9007199254740991", 400)
    image_id = hashlib.sha256(f"{user_id}\n{object_url}".encode("utf-8")).hexdigest()[:32]
    client = get_redis_client()
    existing = client.json().get(IMAGE_PREFIX + image_id)
    if existing:
        logger.info("复用已入库图片，userId=%s，imageId=%s", user_id, image_id)
        return existing
    started = time.monotonic()
    logger.info("开始处理图片，userId=%s，imageId=%s", user_id, image_id)
    image_bytes = download_image(oss_url)
    logger.info("OSS 图片下载完成，imageId=%s，bytes=%s", image_id, len(image_bytes))
    with decode_image(image_bytes) as image:
        load_clip_model()
        try:
            logger.info("开始生成图片描述，imageId=%s", image_id)
            description = describe_image(image_bytes, for_search=True)
        except Exception as error:
            logger.error("图片描述生成失败，imageId=%s，异常类型=%s", image_id, type(error).__name__)
            raise ImageProcessError("视觉模型调用失败或未返回有效描述", 502) from None
        logger.info("图片描述生成完成，imageId=%s", image_id)
        logger.info("开始生成 CLIP 图片向量，imageId=%s", image_id)
        embedding = generate_image_embedding(image)
        logger.info("CLIP 图片向量生成完成，imageId=%s，embeddingDim=%s", image_id, EMBEDDING_DIM)
    logger.info("开始生成图片描述向量，imageId=%s", image_id)
    description_embedding = generate_text_embeddings([description])[0].tolist()
    image_data = {
        "imageId": image_id, "userId": user_id, "ossUrl": object_url,
        "description": description, "embedding": embedding, "embeddingDim": EMBEDDING_DIM,
        "descriptionEmbedding": description_embedding,
        "descriptionEmbeddingModel": TEXT_EMBEDDING_MODEL_PATH,
        "createdAt": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
    }
    image_data = save_image_to_redis(image_data)
    logger.info("图片已完整写入 RedisJSON，userId=%s，imageId=%s，耗时=%.1f秒",
                user_id, image_id, time.monotonic() - started)
    return image_data
