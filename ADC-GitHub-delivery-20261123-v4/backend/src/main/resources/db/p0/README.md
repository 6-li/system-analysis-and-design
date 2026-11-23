# P0 第一批隔离迁移

只用于新建、无真实数据的 `adc_p0_batch1_*` 数据库。不是现有 adc_dev/adc_test 或生产库升级脚本。

前置：PG18.6、独立回环端口（禁止5432）、受核验实例、空adc结构、已批准的btree_gist 1.8位于adc、独立owner/app角色。启动器核验database/user/port/data/system identifier；JDBC再次核验database/user/port/version。实际对象为10张原表测试夹具及八列/11列两扩展。

顺序及SHA256以manifest.json为准。V001—V003逐字复用已批准验证SQL；V004只配置该新实例的技术权限，不改变业务岗位。FOR UPDATE需要UPDATE列权限：dentist.dentist_id、appointment_resolution.resolution_id；没有对应业务更新入口。技术账号持有列权限不意味着数据库阻止恶意主键写入，真实账号权限需部署审核。

VersionedMigrations在调用方显式事务内执行；非空结构立即停止，不自动重放，不创建额外历史表/sequence/trigger。外部SQL、校验和、目标指纹及执行记录是本批迁移证据。失败在同事务回滚。提交未知先只读核对，不能再次初始化。

提交后恢复：先备份，再恢复到另一个核验为空的新隔离库，并比较12表行数及内容摘要；不就地覆盖、DROP、TRUNCATE或删除历史。测试启动器完成这一真实演练，保存dump和摘要；结束停止实例并保留目录。本批不是供长期增量部署使用的自动迁移框架。

运行 `node tools/postgresql/p0-batch1/run.cjs` 会新建隔离实例、运行显式p0-it、备份/恢复并停止；每次运行仍必须处于用户数据库写入授权内。秘密只由启动器生成并在进程内传递，不读取既有凭据。合成目录、角色及状态测试字符串不可作为实际业务配置。
