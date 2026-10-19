/** 从实际生成定义提取机器接口及递归模型引用，禁止引入后台路由或部署地址。 */
export function machineOpenApi(generated) {
 const paths=Object.fromEntries(Object.entries(generated.paths).filter(([path])=>path.startsWith('/open-api/v1/')));
 if(Object.keys(paths).length!==8)throw new Error('MACHINE_OPENAPI_PATH_COUNT_CHANGED');
 const schemas={},pending=[];
 const references=value=>{
  if(value&&typeof value==='object'){
   if(typeof value.$ref==='string')pending.push(value.$ref);
   for(const child of Object.values(value))references(child);
  }
 };
 references(paths);
 while(pending.length){
  const reference=pending.shift(),match=/^#\/components\/schemas\/([^/]+)$/.exec(reference);
  if(!match)throw new Error('MACHINE_OPENAPI_REFERENCE_UNSUPPORTED');
  const name=match[1];if(schemas[name])continue;
  const schema=generated.components.schemas[name];if(!schema)throw new Error('MACHINE_OPENAPI_REFERENCE_MISSING');
  schemas[name]=schema;references(schema);
 }
 const security=generated.components.securitySchemes.ApplicationBearer;
 if(!security||security.scheme!=='bearer')throw new Error('MACHINE_OPENAPI_SECURITY_MISSING');
 return {openapi:generated.openapi,info:{title:'SupportOps 应用诊断 API',version:'1.0',description:'单企业独立部署的应用身份接口；实际服务地址由部署者提供。'},
  servers:[{url:'/'}],paths,components:{schemas,securitySchemes:{ApplicationBearer:security}}};
}
