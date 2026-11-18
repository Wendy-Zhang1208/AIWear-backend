import logging
import os
import uuid
from pathlib import Path

from dashscope import MultiModalConversation
from langchain_core.tools import tool
from werkzeug.datastructures import FileStorage

from edit_image.validate_image import transform_image_to_str

IMAGE_EDIT_MODEL = "qwen-image-3.0"
logger = logging.getLogger(__name__)


def save_image(image_file: FileStorage) -> str:
    # 获取当前文件所在的目录
    current_dir = os.path.dirname(os.path.abspath(__file__))

    # 从上传文件中获取原始扩展名
    original_filename = image_file.filename or ""
    ext = os.path.splitext(original_filename)[1]
    if not ext:
        ext = ".png"  # 默认扩展名

    # 使用 UUID 生成唯一文件名，避免文件名冲突
    unique_filename = f"{uuid.uuid4().hex}{ext}"

    # 拼接完整保存路径
    save_path = os.path.join(current_dir, unique_filename)

    # 保存图片到本地
    image_file.save(save_path)

    # 返回保存后的路径
    return save_path


def delete_image(image_path: str | None) -> None:
    if image_path is None:
        return
    try:
        Path(image_path).unlink(missing_ok=True)
        logger.info("编辑临时图片已清理，path=%s", image_path)
    except OSError as error:
        # 清理失败只记录日志，避免把已经成功的编辑误判为失败。
        logger.warning("编辑临时图片清理失败，path=%s，原因=%s", image_path, error)


# ================= Agent 工具：图片编辑 / 图片合并 =================

def _call_image_edit_model(image_paths: list, instruction: str) -> str:
    """图片编辑/合并的公共调用逻辑：
    1. 根据路径从本地校验并读取图片，转成 Base64 data URI；
    2. 调用图像编辑模型，按指令对图片进行编辑；
    3. 返回编辑后的图片 URL。
    """
    # 1. 校验本地图片是否存在
    for path in image_paths:
        if not path or not os.path.isfile(path):
            raise FileNotFoundError(f"图片文件不存在: {path}")

    # 2. 校验模型名称是否已填写
    if not IMAGE_EDIT_MODEL:
        raise ValueError("图像编辑模型未配置，请先填写 IMAGE_EDIT_MODEL 的具体模型名称")

    # 3. 组装多模态消息：每张图片一个 image 元素，最后附上文本编辑指令
    content = []
    for path in image_paths:
        with open(path, "rb") as image_file:
            content.append({"image": transform_image_to_str(image_file.read())})
    content.append({"text": instruction})
    messages = [{"role": "user", "content": content}]

    # 4. 调用图像编辑模型
    rsp = MultiModalConversation.call(
        model=IMAGE_EDIT_MODEL,
        messages=messages,
    )

    # 5. 解析结果，返回编辑后的图片 URL
    if rsp.status_code != 200:
        raise RuntimeError(
            f"图像编辑模型调用失败: status_code={rsp.status_code}, "
            f"code={rsp.code}, message={rsp.message}"
        )
    choices = getattr(rsp.output, "choices", None) or []
    if not choices or not choices[0].message:
        raise RuntimeError("图像编辑模型未返回结果")
    result_content = choices[0].message.content or []
    for item in result_content:
        if isinstance(item, dict) and isinstance(item.get("image"), str) and item["image"]:
            return item["image"]
    raise RuntimeError(f"模型返回内容中未找到图片URL: {result_content}")


@tool
def edit_image(image_path: str, instruction: str) -> str:
    """图片编辑工具：根据路径读取本地图片 image_path，按照编辑指令 instruction 对图片进行编辑，返回编辑后的图片URL。"""
    return _call_image_edit_model([image_path], instruction)


@tool
def merge_images(image_path1: str, image_path2: str, instruction: str) -> str:
    """图片合并工具：根据路径读取两张本地图片 image_path1 和 image_path2，按照指令 instruction 对两张图片进行合并编辑，返回合并后的图片URL。"""
    return _call_image_edit_model([image_path1, image_path2], instruction)
