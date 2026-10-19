import {randomUUID} from 'node:crypto';

/** 仅用于真实 V11 旧程序升级验收的合成请求，不供新应用执行。 */
export const manual = () => ({type:'MANUAL_OBSERVATION',summary:'合成人员陈述',verificationAction:'处理后重新请求订单接口',observation:'观察到HTTP 200，业务记录正确',observedAt:new Date().toISOString()});
export const outcome = (version=0,status='RESOLVED') => ({requestId:randomUUID(),expectedVersion:version,status,actionsTaken:'OUTCOME_PRIVATE_SENTINEL 人员确认完成配置核对',
  verificationSummary:'独立人工观察，非模型判定',remainingIssue:status==='RESOLVED'?'':'保留未验证部分',changeReason:version?'再次人工核对':'',evidence:['RESOLVED','PARTIALLY_RESOLVED'].includes(status)?[manual()]:[]});
export const draft = (scenario,name='开发合成案例',usageGroup='DEVELOPMENT') => ({name,symptomTags:['合成验收'],sourceApplication:'OrderBridge',sourceVersion:'2.0',
  question:`OC_QUESTION_${randomUUID()} 当前订单同步失败，请核对环境和适用资料。`,sourceDescription:'人员确认来源，仅用受控材料复现',usageGroup,
  adapterId:'ORDERBRIDGE_BUILTIN_V1',scenario,materialPackageId:'ORDERBRIDGE_MATERIALS_V1',reproductionDifference:'原现场仅引用记录，本次使用内置合成场景，不能归因真实处理效果',
  criteria:{requiredFacts:['CRITERIA_PRIVATE_SENTINEL 核对当前现场状态'],citationRequirements:['引用当前版本资料及原文位置'],forbiddenConclusions:['不得直接断言已修复'],allowedUncertainty:['资料不足应说明缺口']}});

