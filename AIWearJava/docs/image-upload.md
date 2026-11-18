# 图片上传接口开发与验收说明

## 项目分析与改动范围

项目使用 Java 17、Spring Boot 4.0.0、MyBatis-Plus Boot 4 Starter 3.5.18、JJWT 0.11.5、阿里云 OSS SDK 3.17.4，数据库驱动为 MySQL Connector/J。`pom.xml` 中 `mybatis-plus.version=3.5.7` 没有被 Starter 的版本声明引用，实际使用的是直接声明的 3.5.18。本次不修改这些版本，也不添加 Maven 依赖。

`UserController` 使用构造器注入、Service 接口和实现类、`@ApiLog`、`Result<T>`；`UserMapper` 继承 `BaseMapper`，实体使用 Lombok 和 MyBatis-Plus 注解。新增上传接口沿用这些方式，响应对象放在已有的 `dto/response` 包中，不另建 VO 包。

现有项目没有统一登录拦截器、用户上下文、自定义业务异常或 `@ControllerAdvice`。已有 `jwtUtil.parseToken()` 提取 Bearer Token，`getUserId()` 验证 JWT 签名、有效期并获取 `userId`。登录时 JWT 保存在 Redis 的 `jwt:user:token:{userId}`，退出时删除。本接口复用 JWT 工具，并检查该 Redis 登录态，防止已退出的旧 Token 继续上传。认证失败返回 `Result.code=401`。

`ApiLogAspect` 会记录 Controller 参数、结果，并将未处理异常统一转换成通用 500。本接口在 Controller 内将预期的业务异常转换为 `Result`，让切面记录并返回；通过 `HttpServletRequest` 读取 Authorization，避免把 Token 作为切面记录的方法参数。Multipart 解析错误可能发生在 Controller 之前，由新增异常处理器按 POST 方法和精确路径处理，其余路径继续抛给原有处理流程。

**已有 Java 文件、前端、数据库结构和 `pom.xml` 均未修改。** `application.yml` 追加 OSS 配置，并在用户明确确认后，将 multipart 两项上限由 10MB 调整为单文件 50MB、总请求 51MB。

## 文件清单

以下路径相对于 `AIWearJava`：

| 新增文件 | 用途 |
| --- | --- |
| `src/main/java/com/aiwear/controller/FileController.java` | 接收 file，调用 Service，返回 Result，复用 ApiLog |
| `src/main/java/com/aiwear/service/FileService.java` | 图片上传业务接口 |
| `src/main/java/com/aiwear/service/impl/FileServiceImpl.java` | 认证、文件校验、OSS 上传、短事务写入和补偿删除 |
| `src/main/java/com/aiwear/mapper/FileMapper.java` | 通过 BaseMapper 操作已有 files 表，不新增 XML |
| `src/main/java/com/aiwear/entity/FileEntity.java` | 映射 files 的五个字段，ID 使用数据库自增 |
| `src/main/java/com/aiwear/dto/response/FileUploadResponse.java` | 返回 url、fileName、fileSize |
| `src/main/java/com/aiwear/properties/OssProperties.java` | 绑定并校验 OSS 配置，不生成包含密钥的 toString |
| `src/main/java/com/aiwear/config/OssConfig.java` | 注册共享、延迟初始化的 OSS Client，应用关闭时 shutdown |
| `src/main/java/com/aiwear/exception/FileUploadException.java` | 本接口可公开的业务错误码和提示 |
| `src/main/java/com/aiwear/exception/FileUploadExceptionHandler.java` | 处理上传路径的 multipart 解析与超限错误 |
| `src/test/java/com/aiwear/FileUploadRegressionTest.java` | 无新增依赖的业务与真实 HTTP 回归检查，外部服务使用替身 |
| `scripts/test-file-upload.ps1` | 编译并执行回归入口 |
| `docs/multipart-50mb.patch` | 经确认并已应用的两行公共配置最小补丁，保留供审阅 |
| `docs/image-upload.md` | 本说明与手工验收步骤 |

## 配置与启动

已有依赖可以直接复用：

```xml
<dependency>
    <groupId>com.aliyun.oss</groupId>
    <artifactId>aliyun-sdk-oss</artifactId>
    <version>${oss.version}</version>
</dependency>
```

`${oss.version}` 已经是 `3.17.4`，不要重复添加。

`application.yml` 新增配置：

```yaml
aliyun:
    oss:
        endpoint: oss-cn-beijing.aliyuncs.com
        access-key-id: ${ALIYUN_OSS_ACCESS_KEY_ID:}
        access-key-secret: ${ALIYUN_OSS_ACCESS_KEY_SECRET:}
        bucket-name: bite-lz
        max-size: 52428800
        max-pixels: 25000000
```

`endpoint` 填写地域主机名，不加 `https://`、Bucket 或路径。`max-pixels` 是为防止图片解码内存耗尽增加的像素上限，默认允许最多 2500 万像素，可以调低。文件上限可以调低，不能超过本需求的 52,428,800 字节。日期目录使用后端服务器的本地日期。

在启动后端的环境或 IDEA 运行配置中设置：

```powershell
$env:ALIYUN_OSS_ACCESS_KEY_ID = '<你的 AccessKey ID>'
$env:ALIYUN_OSS_ACCESS_KEY_SECRET = '<你的 AccessKey Secret>'
```

然后启动 `com.aiwear.AIWearApplication`，默认端口为 8081。MySQL、已有 users/files 表和 Redis 需要可访问。OSS 配置为空时客户端不会在启动阶段创建，上传接口会返回配置缺失提示；填写后需要重启应用。实际 Bucket 地域必须与 Endpoint 一致，执行身份至少需要对 `bite-lz/images/*` 的 `oss:PutObject`、`oss:DeleteObject` 权限；不需要修改 Bucket ACL。

### 经确认的 multipart 公共配置

原有 `spring.servlet.multipart.max-file-size` 和 `max-request-size` 均为 `10MB`，不足以接收 50MB 图片。用户明确确认后，已将两项改为 `50MB` 和 `51MB`，与 `aliyun.oss.max-size` 的 52,428,800 字节业务上限配合使用。

最小调整为：

```diff
-            max-file-size: 10MB
-            max-request-size: 10MB
+            max-file-size: 50MB
+            max-request-size: 51MB
```

总请求上限多留 1MB，容纳 multipart 边界、文件名与其他表单字段。该修改会提高应用的 multipart 接收上限，不改 JWT、接口返回、SQL 或原有业务。补丁已应用，`docs/multipart-50mb.patch` 保留为具体改动记录，无需重复应用。Spring Boot 的单文件与总请求上限是两项独立配置，见[官方配置说明](https://docs.spring.io/spring-boot/4.0/appendix/application-properties/)。

## 接口与调用链

`POST /api/file/upload/image`，请求头为 `Authorization: Bearer <token>`，请求体使用 `multipart/form-data`，文件字段名是 `file`。不接收业务参数 userId，额外伪造同名表单字段也不会改变记录归属。

成功响应保持项目现有的 **msg** 字段，不使用示例中的 message：

```json
{
  "code": 200,
  "msg": "图片上传成功",
  "data": {
    "url": "https://bite-lz.oss-cn-beijing.aliyuncs.com/images/2026/11/15/550e8400-e29b-41d4-a716-446655440000.png",
    "fileName": "photo.png",
    "fileSize": 102400
  }
}
```

沿用现有项目约定，业务错误通常仍以 HTTP 200 携带 `Result.code=400/401/500` 返回，前端应读取 code；没有修改 Result 或引入新的 HTTP 状态约定。

完整流程：

1. `FileController` 接收 MultipartFile，读取 Authorization，调用 `FileServiceImpl`。
2. 通过现有 jwtUtil 验证 Token 并提取用户 ID，核对 Redis 当前登录态。未登录、过期、签名无效或已退出立即失败。
3. 检查非空、文件字节数、清理后的文件名长度和扩展名。允许 JPG、JPEG、PNG，拒绝 SVG、GIF、WEBP 等本次未启用的类型。
4. 使用 JDK ImageIO 从输入流识别真实格式，与后缀比较；先检查分辨率，再完整解码验证。不会信任客户端 Content-Type，不会只检查文件头。JDK 标准支持 JPEG、PNG，未内置 WEBP 解码器，因此没有为了 WEBP 新增第三方依赖，见[JDK 标准插件列表](https://docs.oracle.com/en/java/javase/17/docs/api/java.desktop/javax/imageio/package-summary.html)。
5. 生成 `images/yyyy/MM/dd/UUID.后缀`，检查稳定 URL 长度，使用共享 OSS Client 和新的文件输入流上传。元数据设置实际 MultipartFile 大小与经过验证的 MIME 类型；输入流和 ImageReader 都会关闭。
6. OSS 上传成功后，才通过 TransactionTemplate 开启独立短事务，调用 `FileMapper.insert()`。数据库生成 ID；驼峰配置把 userId、fileName、fileSize、ossUrl 映射到表中对应列。
7. 等待事务提交成功后返回三个结果字段，Controller 封装 Result，ApiLog 记录并返回。不会把图片转换成 Base64 或用 getBytes() 整体加载进行上传。Multipart 解析本身可能使用 Servlet 临时文件，由框架在请求结束时清理；业务代码不保存长期本地副本。

## 数据一致性与异常

文件校验失败时，不访问 OSS、不写数据库。上传异常时不写数据库，并尝试删除本次 UUID 对应对象，因为网络超时不一定代表 OSS 没有接收到文件。

插入失败、影响行数不为 1 或事务最终提交抛出异常时，接口返回失败，并尝试删除刚上传的 OSS 对象。短事务只包围 insert；没有把网络上传包在数据库事务里。`REQUIRES_NEW` 确保数据库操作不会加入尚未提交的调用方事务，提交异常能在返回成功前被捕获。TransactionTemplate 是 Spring 自带能力，见[官方事务说明](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)。

删除失败不会吞掉原始上传失败结果，会记录 Object Key 和异常类型；OSS 服务端异常还记录 errorCode、requestId。不会记录原始 SDK 请求、AccessKey Secret 或将内部异常堆栈返回前端。

这属于补偿机制，不能提供严格的分布式事务。进程在上传后崩溃、补偿删除失败，或者 OSS 请求超时但服务端稍后才完成，都可能遗留对象。如果数据库提交请求已经到达 MySQL、但网络在确认提交结果前断开，提交结果也可能不确定，补偿删除后可能存在需要核对的记录。应根据错误日志中的 Object Key 对照 OSS 和 files 人工核查，不能简单认为收到 500 就一定没有产生任何状态。files 还需要使用支持事务的存储引擎（例如 InnoDB），可用下文 SQL 核实。

## 前端手工测试步骤

1. 配好 OSS 环境变量，启动 MySQL、Redis 和 Java 服务。multipart 的 50MB/51MB 配置已应用，修改配置后需要重启已有后端进程。
2. 在 `aiwear-frontend` 启动现有前端：`npm run dev`；默认访问 `http://127.0.0.1:5173`，Vite 已将 `/api` 代理到 `http://127.0.0.1:8081`。若使用部署好的前端，确认其代理指向本次 Java 服务，而不是模拟后端。
3. 使用已有登录页登录，打开“我的素材” `/images`，点击“上传图片”，选择 PNG 或 JPG。
4. 打开浏览器开发者工具 Network，查看 `POST /api/file/upload/image`：应有 Bearer Token，Form Data 中 `file` 是二进制文件，响应 `code=200`，data 包含三个字段。
5. 现有页面的 `uploadMyImage(file)` 已与本接口对齐，不需要改前端。页面上传后会另发 `GET /api/file/my-images`；当前 Java 项目没有实现该列表接口，本次也未扩展它，因此应以 POST 响应、OSS 对象和 SQL 为上传验收依据，列表刷新失败不能单独判断上传失败。

浏览器会自动为 FormData 加上带 boundary 的 Content-Type，不要手动设置为不含 boundary 的 `multipart/form-data`。在浏览器控制台可运行以下代码，选择文件并查看原始响应；Token 从现有前端 localStorage 读取：

```javascript
const picker = document.createElement('input');
picker.type = 'file';
picker.accept = 'image/png,image/jpeg';
picker.onchange = async () => {
  const form = new FormData();
  form.append('file', picker.files[0]);
  const response = await fetch('/api/file/upload/image', {
    method: 'POST',
    headers: { Authorization: `Bearer ${localStorage.getItem('aiwear_token')}` },
    body: form
  });
  console.log(await response.json());
};
picker.click();
```

PowerShell 下也可以直接调用后端（替换 Token 和文件路径）：

```powershell
curl.exe -X POST 'http://127.0.0.1:8081/api/file/upload/image' `
  -H 'Authorization: Bearer <登录返回的Token>' `
  -F 'file=@C:/images/photo.png'
```

### 验收场景

负面场景可以通过开发者工具的“编辑并重新发送”、上面的 fetch 或 Postman 执行，以免前端文件选择器的检查先拦截请求。每次失败都应对照上传前后的 SQL 记录数。

| 场景 | 操作与预期 |
| --- | --- |
| 正常 PNG | POST 返回 200；OSS 有对象，files 新增一行，大小与本地文件一致 |
| 正常 JPG/JPEG | 返回正确 URL、原始文件名、字节数；OSS Content-Type 为 image/jpeg |
| 未携带 Token | 删除 Authorization 后请求，Result.code=401，无上传或数据库写入 |
| Token 无效或过期 | 改动 Token 任意字符、使用已过期 Token，均返回 401 |
| 已退出登录 | 保存旧 Token，正常退出后再用旧 Token 请求，返回 401 |
| 缺少 file | 仅传普通表单字段，返回 400、图片文件不能为空 |
| 空文件 | 上传零字节 .png，返回 400，无副作用 |
| 超过 50MB | 传 52,428,801 字节以上文件，返回 400，无副作用 |
| 非图片 | 上传 .txt/.exe/.svg/.webp，返回 400 |
| 伪装 PNG | 将文本或 SVG 改名 .png，即使 Content-Type 为 image/png 也返回 400 |
| 后缀内容不一致 | 将 JPEG 直接改为 .png，返回 400 |
| 同名多次上传 | 连续上传两次 photo.png，URL 中 UUID 不同，OSS 和 files 都保留两份 |
| OSS 上传失败 | 在专用测试环境使用不可写的 OSS 凭据，返回 500，files 不新增；不要直接破坏正式环境配置 |
| OSS 成功但 DB 保存失败 | 在专用测试环境让数据库测试账号没有 files 的 INSERT 权限，OSS 仍可写/删，返回 500，随后控制台确认新对象已删除 |
| 事务提交失败 | 回归入口通过事务管理器替身单独模拟，确认执行提交后才判断成功，失败会尝试删除 |
| 补偿删除失败 | 回归入口模拟上传成功、数据库失败、删除失败，确认返回失败并记录 Object Key |
| 伪造 userId | 增加 form.append('userId', '99999999')，成功记录的 user_id 仍是 Token 中用户 |

### OSS 控制台核实

进入阿里云 OSS 控制台，选择地域与 `bite-lz` Bucket，在文件列表按 `images/年/月/日/` 目录查找响应 URL 最后一段 UUID 文件名。核对对象大小、最近修改时间、Content-Type；连续同名上传应出现不同对象。失败补偿测试则用日志中的完整 Object Key 查询，预期对象不存在。控制台可预览或下载以确认内容，界面操作可参考[阿里云控制台说明](https://www.alibabacloud.com/help/en/oss/user-guide/console-quick-start)。

### SQL 核实

```sql
-- 只读检查表的事务引擎，不创建或修改表。
SHOW TABLE STATUS FROM bite_wear LIKE 'files';

-- 把 10000001 替换为登录响应中的真实用户 ID。
SELECT id, user_id, file_name, file_size, oss_url
FROM bite_wear.files
WHERE user_id = 10000001
ORDER BY id DESC
LIMIT 10;

-- 比较上传或失败测试前后的数量。
SELECT COUNT(*) AS file_count
FROM bite_wear.files
WHERE user_id = 10000001;
```

数据库应存清理后的原始文件名、自增 ID 和没有过期签名参数的稳定地址。不要要求自增 ID 连续；事务回滚后自增值出现间隙是正常现象。

## 私有 Bucket 与稳定 URL

当前接口和数据库都使用稳定的 `https://Bucket.Endpoint/ObjectKey` 地址，不读取或改变 Bucket ACL。私有 Bucket 的稳定地址直接在浏览器打开可能返回 403；这不代表上传失败。

如果业务需要前端直接显示私有图片，应在独立的受认证读取接口中，先检查 files.user_id，再由后端 SDK 根据对象信息生成短期签名 URL。也可以在确认需求后让上传接口仅返回临时签名 URL，但数据库仍保存稳定地址；临时 URL 到期后需重新申请。本次保持题目规定的稳定 URL 返回行为，没有擅自改为公开读。区别可参考[阿里云固定地址与私有访问说明](https://www.alibabacloud.com/help/en/oss/use-a-fixed-file-url-to-access-a-file)。

## 自动验证与待确认事项

本次验证结果：Maven 编译通过；44 项业务与 HTTP 回归检查全部通过；使用项目原有 `AIWearApplication` 和 `application.yml` 完成实际启动检查，Tomcat 与 MyBatis-Plus 正常加载，检查后已停止临时应用。未使用真实 OSS 密钥上传，也未向真实数据库写入测试记录。

在 Java 17 和 Maven 可用的 PowerShell 中执行：

```powershell
cd C:/Users/26933/Agent-Projects/ClothChange/AIWearJava
./scripts/test-file-upload.ps1
# 依赖已缓存时可以使用离线模式：
./scripts/test-file-upload.ps1 -Offline
```

入口使用现有依赖编译生产代码和测试代码，再直接执行 Java main；没有添加 JUnit，因此不能将只运行 `mvn test` 等同于执行了这些回归检查。测试使用真实 JJWT、真实 ImageIO、实际嵌入式 Tomcat HTTP 请求和现有 ApiLog，OSS、Redis、数据库使用替身，不使用真实云凭据。独立 HTTP 测试应用直接读取项目 application.yml 中的 multipart 限额，不通过测试启动参数覆盖它，验证实际配置的 50MB 与超限行为。启动本机 HTTP 服务需要环境允许回环连接。

需要在真实环境继续确认：OSS 密钥和写/删权限、Bucket 的实际地域和读权限、数据库 files 表及其事务引擎、MySQL 和 Redis 连通性，以及反向代理的请求大小和超时限制。图片解码仍会使用内存，25MP 限制不能替代部署层的并发和流量控制。真实 OSS/MySQL 联调未在替身测试中完成。

## 通俗解释

前端把图片放进一个名为 file 的表单格子，并附上登录凭证。后端先核对“是谁上传的”，再检查“这个文件是否真的是允许的图片、大小是否合规”。通过后，后端给图片取一个不会轻易重复的 UUID 名称，按日期放到 OSS。

OSS 保存完成后，后端才在 MySQL 记一张“索引卡”：谁上传、原来叫什么、多少字节、放在哪里。索引卡提交成功才告诉前端上传成功，并返回地址。如果记卡片失败，后端会尝试把刚存到 OSS 的图片删掉。OSS 和数据库是两个系统，这个删除是补救步骤；进程崩溃或补救失败时，仍需按日志核对和清理。
