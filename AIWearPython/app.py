import json
import logging
from typing import Literal

from flask import Flask, jsonify, request
from langchain.agents import create_agent
from langchain.agents.structured_output import ToolStrategy
from langchain_community.chat_models import ChatTongyi
from langchain_core.messages import ToolMessage
from pydantic import BaseModel, Field, model_validator
from redis.exceptions import RedisError
from werkzeug.exceptions import BadRequest, RequestEntityTooLarge

from edit_image.edit_image import delete_image, edit_image, merge_images, save_image
from edit_image.validate_image import check_image, describe_image
from image_store import (
    MAX_IMAGE_BYTES, ImageProcessError, decode_image, delete_image_from_redis,
    init_image_index, process_image, search_images,
)


app = Flask(__name__)


class ImageEditResponse(BaseModel):
    """图片编辑任务的最终结果。"""

    result: Literal["success", "failed"] = Field(
        description="图片编辑成功为 success，否则为 failed"
    )
    image_url: str = Field(description="工具返回的图片 URL；没有生成图片时为空字符串")
    message: str = Field(default="", description="对用户的简短说明或无法完成的原因")

    @model_validator(mode="after")
    def validate_image_url(self):
        if self.result == "success" and not self.image_url:
            raise ValueError("图片编辑成功时必须提供图片 URL")
        if self.result == "failed" and self.image_url:
            raise ValueError("图片编辑失败时图片 URL 必须为空")
        return self


class AgentChatTongyi(ChatTongyi):
    """兼容 LangChain 结构化输出与通义千问的工具选择参数。"""

    def bind_tools(self, tools, **kwargs):
        # ToolStrategy 使用 "any" 强制工具调用；通义千问使用 "auto"。
        if kwargs.get("tool_choice") == "any":
            kwargs["tool_choice"] = "auto"
        return super().bind_tools(tools, **kwargs)


llm = AgentChatTongyi(model_name="qwen-plus", temperature=0.7)

agent = create_agent(
    model=llm,
    tools=[edit_image, merge_images],
    response_format=ToolStrategy(ImageEditResponse),
    system_prompt=(
        "你是一个专业的图像编辑助手。根据用户的需求使用提供的图片路径调用工具；"
        "请先等待图片工具执行完成，再通过 ImageEditResponse 工具返回最终结果。"
        "成功时 image_url 必须原样使用图片工具返回的 URL；"
        "无法完成时 result 为 failed，image_url 为空，message 中向用户说明原因或回答问题。"
    ),
)


def agent_invoke(message: str) -> dict:
    state = agent.invoke({"messages": [{"role": "user", "content": message}]})
    # 工具返回的是图片 URL，必须以实际工具结果为准，不能采信模型编造的 URL。
    image_urls = []
    for item in state["messages"]:
        if (
            isinstance(item, ToolMessage)
            and item.name in {"edit_image", "merge_images"}
            and item.status == "success"
            and isinstance(item.content, str)
            and item.content.startswith(("https://", "http://"))
        ):
            image_urls.append(item.content)

    response = state.get("structured_response")
    
    if response is None:
        # Qwen 的 auto 模式可能直接回复文本；保证 HTTP 响应仍有固定结构。
        last_content = state["messages"][-1].content
        message_text = last_content if isinstance(last_content, str) else ""
        response = ImageEditResponse(
            result="success" if image_urls else "failed",
            image_url=image_urls[-1] if image_urls else "",
            message=message_text,
        )
    if response.result == "success" and response.image_url not in image_urls:
        raise RuntimeError("Agent 返回的图片 URL 与工具结果不一致")
    return response.model_dump()


@app.route('/app/skills', methods=['POST'])
def image_agent():
    try:
        # 合并最多接收两张各10MB的图片，另留1MB容纳表单内容。
        request.max_content_length = 2 * MAX_IMAGE_BYTES + 1024 * 1024
        instruction = request.form.get('instruction', '').strip()
        if not instruction:
            return jsonify({'code': 400, 'info': '用户输入不能为空'}), 400

        image_file1 = request.files.get('image1')
        if image_file1 is None or not image_file1.filename:
            return jsonify({'code': 400, 'info': '第一张图片为空'}), 400

        image_file2 = request.files.get('image2')
        if image_file2 is not None and not image_file2.filename:
            return jsonify({'code': 400, 'info': '第二张图片为空'}), 400

        # 先检查全部输入大小，避免第二张超限时已经保存第一张素材。
        for image_file in (image_file1, image_file2):
            if image_file is not None:
                image_file.stream.seek(0, 2)
                image_size = image_file.stream.tell()
                image_file.stream.seek(0)
                if image_size > MAX_IMAGE_BYTES:
                    app.logger.warning('编辑或合并图片超过10MB，bytes=%s', image_size)
                    return jsonify({'code': 400, 'info': '每张图片不能超过10MB'}), 400

        image_path1 = save_image(image_file1)
        image_path2 = save_image(image_file2) if image_file2 is not None else None

        # qwen-plus 是文本模型，路径和指令作为一条文本消息交给 Agent。
        task = {'instruction': instruction, 'image_path1': image_path1}
        if image_path2 is not None:
            task['image_path2'] = image_path2
        app.logger.info('开始执行图片编辑任务，image1=%s，image2=%s', image_path1, image_path2)
        result = agent_invoke(json.dumps(task, ensure_ascii=False))
        # Agent 成功生成结果后才清理素材，失败时保留，便于排查和重试。
        if result['result'] == 'success':
            delete_image(image_path1)
            delete_image(image_path2)
        else:
            app.logger.warning('图片编辑未成功，保留素材，image1=%s，image2=%s', image_path1, image_path2)
        return jsonify({'code': 200, 'info': 'agent响应', 'data': result}), 200

    except RequestEntityTooLarge:
        app.logger.warning('编辑或合并请求超过大小限制，contentLength=%s', request.content_length)
        return jsonify({'code': 400, 'info': '每张图片不能超过10MB'}), 400
    except Exception:
        app.logger.exception('agent过程发生异常')
        return jsonify({'code': 500, 'info': 'agent执行失败，请查看服务端日志'}), 500


@app.route('/app/validate_image', methods=['POST'])
def validate():
    try:
        request.max_content_length = MAX_IMAGE_BYTES + 1024 * 1024
        if 'image' not in request.files:
            return jsonify({'code': 400, 'info': '请求中未找到图片文件'}), 400

        image_file = request.files['image']
        if image_file.filename == '':
            return jsonify({'code': 400, 'info': '未选择任何图片文件'}), 400

        image_bytes = image_file.read(MAX_IMAGE_BYTES + 1)
        if not image_bytes:
            return jsonify({'code': 400, 'info': '图片文件为空'}), 400
        if len(image_bytes) > MAX_IMAGE_BYTES:
            app.logger.warning('待校验图片超过10MB，bytes=%s', len(image_bytes))
            return jsonify({'code': 400, 'info': '图片文件不能超过10MB'}), 400

        description = describe_image(image_bytes)
        if not description:
            return jsonify({'code': 500, 'info': '图片描述失败，未获取到有效内容'}), 500

        result = check_image(description)
        return jsonify({'code': 200, 'info': '校验成功', 'success': result}), 200

    except RequestEntityTooLarge:
        app.logger.warning('图片校验请求超过大小限制，contentLength=%s', request.content_length)
        return jsonify({'code': 400, 'info': '图片文件不能超过10MB'}), 400
    except Exception:
        app.logger.exception('图片校验过程发生异常')
        return jsonify({'code': 500, 'info': '图片校验失败，请查看服务端日志'}), 500


@app.route('/api/upload-image', methods=['POST'])
def upload_image():
    try:
        # 16 KiB 限制的是 URL 和用户ID组成的 JSON，不是 OSS 中的图片大小。
        request.max_content_length = 16 * 1024
        if not request.is_json:
            raise ImageProcessError('请求必须使用 application/json', 400)
        data = request.get_json()
        if not isinstance(data, dict):
            raise ImageProcessError('请求体必须是包含 ossUrl 和 userId 的 JSON 对象，不能只传图片地址', 400)
        if 'ossUrl' not in data or 'userId' not in data:
            raise ImageProcessError('请求体必须包含 ossUrl 和 userId；ossUrl 填写 Java 上传响应中的 data.url', 400)
        result = process_image(data.get('ossUrl'), data.get('userId'))
        return jsonify({
            'description': result['description'], 'embeddingDim': result['embeddingDim'],
            'imageId': result['imageId'], 'success': True,
        }), 200
    except RequestEntityTooLarge:
        app.logger.warning('图片入库 JSON 请求体超过16 KiB，contentLength=%s', request.content_length)
        return jsonify({
            'success': False,
            'message': 'JSON 请求体不能超过16 KiB，仅传 ossUrl 和 userId，不要传图片文件或 Base64',
        }), 400
    except BadRequest:
        # 不记录原始 Body，避免 OSS 签名、图片 Base64 等进入日志。
        app.logger.warning('图片入库 JSON 解析失败，contentLength=%s，mimetype=%s',
                           request.content_length, request.mimetype)
        return jsonify({
            'success': False,
            'message': '请求体不是有效 JSON，请传入包含 ossUrl 和 userId 的 JSON 对象，不能直接粘贴图片 URL 或 Markdown 链接',
        }), 400
    except ImageProcessError as error:
        app.logger.warning('图片入库未完成，HTTP状态=%s，原因=%s', error.status, str(error))
        return jsonify({'success': False, 'message': str(error)}), error.status
    except RedisError as error:
        app.logger.error('图片入库 Redis 操作失败，异常类型=%s', type(error).__name__)
        return jsonify({'success': False, 'message': 'Redis 暂时不可用，请检查连接、模块和权限'}), 503
    except Exception as error:
        app.logger.error('图片入库内部异常，异常类型=%s', type(error).__name__)
        return jsonify({'success': False, 'message': '图片处理失败，请查看服务端日志'}), 500


@app.route('/api/delete-image', methods=['POST'])
def delete_stored_image():
    try:
        request.max_content_length = 16 * 1024
        if not request.is_json:
            raise ImageProcessError('请求必须使用 application/json', 400)
        data = request.get_json()
        if not isinstance(data, dict):
            raise ImageProcessError('请求体必须包含 ossUrl 和 userId', 400)
        delete_image_from_redis(data.get('ossUrl'), data.get('userId'))
        return jsonify({'success': True}), 200
    except (BadRequest, RequestEntityTooLarge):
        return jsonify({'success': False, 'message': '删除请求格式不正确'}), 400
    except ImageProcessError as error:
        return jsonify({'success': False, 'message': str(error)}), error.status
    except RedisError as error:
        app.logger.error('图片缓存删除失败，异常类型=%s', type(error).__name__)
        return jsonify({'success': False, 'message': '图片删除服务暂时不可用，请稍后重试'}), 503
    except Exception as error:
        app.logger.error('图片删除失败，异常类型=%s', type(error).__name__)
        return jsonify({'success': False, 'message': '图片删除失败，请稍后重试'}), 500


@app.route('/api/search-image', methods=['POST'])
def search_image():
    try:
        request.max_content_length = MAX_IMAGE_BYTES + 1024 * 1024
        if request.mimetype != 'multipart/form-data':
            raise ImageProcessError('请求必须使用 multipart/form-data', 400)
        user_id = request.form.get('userId', '').strip()
        if not user_id.isascii() or not user_id.isdecimal() or len(user_id) > 16:
            raise ImageProcessError('userId 必须是有效的正整数', 400)
        user_id = int(user_id)
        # 与现有入库及 Redis NUMERIC 的精确整数范围一致。
        if not 0 < user_id <= 9007199254740991:
            raise ImageProcessError('userId 必须是有效的正整数，且不超过 9007199254740991', 400)

        query = request.form.get('query')
        image_file = request.files.get('file')
        if (query is None) == (image_file is None):
            raise ImageProcessError('query 与 file 必须且只能提供一个', 400)
        if query is not None:
            query = query.strip()
            if not query:
                raise ImageProcessError('查询文本不能为空', 400)
            results = search_images(user_id, query=query)
        else:
            if not image_file.filename:
                raise ImageProcessError('查询图片不能为空', 400)
            # 查询图片仅在内存中解码，不上传 OSS，也不写数据库或 Redis。
            with decode_image(image_file.read(MAX_IMAGE_BYTES + 1)) as image:
                results = search_images(user_id, image=image)
        return jsonify({'code': 200, 'message': '查询成功', 'data': results}), 200
    except (BadRequest, RequestEntityTooLarge):
        return jsonify({'code': 400, 'message': '请求格式不正确或图片超过大小限制', 'data': None}), 400
    except ImageProcessError as error:
        return jsonify({'code': error.status, 'message': str(error), 'data': None}), error.status
    except RedisError as error:
        app.logger.error('图片检索 Redis 操作失败，异常类型=%s', type(error).__name__)
        return jsonify({'code': 503, 'message': 'Redis 暂时不可用，图片检索失败', 'data': None}), 503
    except Exception as error:
        app.logger.error('图片检索失败，异常类型=%s', type(error).__name__)
        return jsonify({'code': 500, 'message': '图片检索失败，请检查模型配置及服务端日志', 'data': None}), 500


if __name__ == '__main__':
    logging.basicConfig(level=logging.INFO)
    try:
        init_image_index()
    except ImageProcessError as error:
        app.logger.error('图片索引初始化未完成，原因=%s', str(error))
    except Exception as error:
        # Redis 故障不影响已有功能；新增接口会返回明确的 503，恢复后可重新初始化。
        app.logger.error('图片索引初始化未完成，异常类型=%s', type(error).__name__)
    # Java 通过本机调用，关闭调试器，避免异常页面暴露内部信息。
    app.run(host='127.0.0.1', port=5000)
