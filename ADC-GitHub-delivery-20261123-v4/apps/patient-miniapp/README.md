# ADC患者小程序

功能：账号登录、本人资料、预约选时与申请、我的预约、历史就诊、医生说明与注意事项、账单明细及模拟结算记录查看。

微信开发者工具导入当前patient-miniapp目录，保留miniprogramRoot为miniprogram/，不要选择外层解压总目录。project.config.json和miniprogram文件夹应位于所选目录同一层。

体验配置在miniprogram/course-preview-config.js；正式服务地址配置在release-config.js。AppID为公共项目标识，AppSecret和私有开发者配置不进入仓库。课程账号与真实微信身份兑换分别配置，正式发布仍需遵守平台域名与认证要求。

从仓库根目录运行node --test apps/patient-miniapp/tests/*.test.cjs可执行离线逻辑检查。手机显示、网络和平台发布需要在微信开发者工具及真机中验证。
