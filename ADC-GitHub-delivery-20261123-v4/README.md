# ADC 牙科连锁诊所运营管理系统

本系统依据课程报告进行设计与实现，提供患者微信小程序和员工岗位工作台，覆盖预约、诊疗、账单、库存、季度经营及牙医排班等功能。

## 系统组成

| 目录 | 内容 |
| --- | --- |
| apps/staff-web | Vue 3 员工网页、组件及测试 |
| apps/patient-miniapp | 原生微信小程序、公共项目配置及测试 |
| backend | Spring Boot 后端、SQL资源与测试 |
| tools | 配置模板、合成测试资料和数据库/部署辅助脚本 |
| specs | 接口与数据文书合同 |
| docs | 使用、架构、构建和测试说明 |

技术栈：Vue 3、Spring Boot、Java 21、PostgreSQL 18、原生微信小程序。课程环境使用合成资料和模拟结算，不进行真实扣款。

- [功能及使用说明](docs/operation-manual.md)
- [构建与运行](docs/build-and-run.md)
- [系统架构](docs/architecture.md)
- [测试说明](docs/testing.md)
- [课程报告目录](docs/report/README.md)
- [网页界面示例](docs/images/staff-workspace.png)
- [小程序界面示例](docs/images/patient-home.png)

员工按岗位登录，菜单和数据操作由后端权限校验。医生可按预约编号定位本人接诊的就诊；预约编号和就诊编号分别保留。患者可查看本人预约、就诊项目、医生说明和账单。

仓库不包含账号密码、数据库备份、运行依赖和构建产物。首次运行前需准备对应环境与配置，详见构建说明。
