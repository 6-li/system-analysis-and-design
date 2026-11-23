# 测试说明

## 本地执行

```powershell
node --test apps/staff-web/tests/*.test.js
node --test apps/patient-miniapp/tests/*.test.cjs
python tools/deploy/test_staff_experience.py
```

后端定向检查可执行：

```powershell
mvn -B -f backend/pom.xml -Dtest=DoctorAppointmentLookupTest,CourseVisitDirectoryTest,CourseStaffLoginTest test
```

后端其他测试源码位于backend/src/test；部分集成/浏览器测试需要独立数据库、受控环境或预先生成的夹具，不能直接视为无环境依赖测试。

## 已执行结果

员工网页单元测试84项通过、0失败，另有1条已有TODO；小程序90项通过、0失败；最近网页后端定向检查11项通过；安装器文件/前置保护4项通过。共享网页与登录界面12组合成浏览器检查通过，检查了七岗位桌面和部分手机布局，未发出业务写请求。

这些结果是对应版本和范围的本地结果，不能替代GitHub本次运行结果或全部业务分支验收。CI显示实际运行结果，不预设全套测试均已执行。

## 自动化范围

本交付工作流执行后端编译与上述定向无数据库测试、员工网页单元测试/构建、小程序单元测试和安装器保护测试。没有运行需要真实服务的浏览器或数据库集成场景。其他业务测试源码保留，可在对应环境下单独运行。

测试使用合成数据，不将模拟身份、医生说明或支付当作真实业务事实。界面图片也采用合成示例数据。


季度记录新增检查：后端历史查询/HTTP接线及受影响服务13项离线Mock测试通过；网页跨页面合成检查覆盖两种宽度下历史记录查回、自动带入、新建业绩显示、财务选择复核和可读导出。无真实数据库执行。新增代码的云端界面验证需在部署后完成，本地结果不等同云端验收。
