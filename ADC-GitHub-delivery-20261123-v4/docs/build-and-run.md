# 构建与运行

## 环境

Java 21、Maven、Node.js 24.12及以上兼容版本、PostgreSQL 18；微信开发者工具用于小程序。员工网页依赖由package-lock.json锁定。

```powershell
mvn -B -f backend/pom.xml -DskipTests package
npm --prefix apps/staff-web ci
npm --prefix apps/staff-web run build
```

构建产物分别在backend/target和apps/staff-web/dist。跳过测试的构建命令仅用于打包，不代表测试通过；测试执行见测试说明。

## 配置与数据库

技术服务默认配置见backend/src/main/resources/application.properties，课程运行配置见application-course-demo.properties。准备数据库连接、岗位账号及运行资料后，使用外部受控配置启动course-demo。示例模板在tools/deploy/course-runtime.env.example，不能把占位符当作有效密码。

SQL资源位于backend/src/main/resources/db与tools/postgresql。部分脚本针对不同环境或阶段，不是可在已有数据库上全部重跑的安装清单。执行前应核对目标库、角色、版本及已有数据，并保留备份。

tools/deploy/update-staff-experience.py是指定已配置环境的代码更新工具，包含严格前置版本校验；它不是空服务器全量安装程序。不得跳过版本检查或删除原数据来满足前置条件。

## 微信小程序

微信开发者工具导入apps/patient-miniapp这一层目录，应同时看到project.config.json与miniprogram文件夹。小程序目录配置为miniprogram/，app.json位于其中。

公开环境开关在miniprogram/course-preview-config.js，正式服务域名配置在release-config.js。仓库保留课程体验配置，不包含AppSecret；正式发布需按微信平台要求配置域名与身份服务。开发者私有配置不上传。

## 启动及使用

部署时保持后端、员工静态网页及小程序所用服务地址一致，按受控账号登录。当前岗位与可用菜单由服务端会话决定，页面显示不替代后端授权校验。首次配置环境不属于源码克隆后自动完成的步骤。
