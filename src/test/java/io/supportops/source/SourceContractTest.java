package io.supportops.source;
import io.supportops.api.support.FixedCommandJson;
import io.supportops.source.service.SourcePackageCodec;
import io.supportops.source.vo.SourcePackage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
/** Java与独立Node契约样例核对，不访问数据库或真实模型。 */
class SourceContractTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final FixedCommandJson json=new FixedCommandJson(mapper,Validation.buildDefaultValidatorFactory().getValidator());
    private final SourcePackageCodec codec=new SourcePackageCodec(json,mapper);
    /** 所有信封字段参与摘要，中文UTF-8与NFC规范保持一致。 */
    @Test void verifiesSharedCanonicalFixture() throws Exception {
        SourcePackage value=json.decode(Files.readString(Path.of("contracts/source-snapshot/v1/example.json")),SourcePackage.class);
        codec.validate(value);
        assertEquals("dd77b3ce0a011fe30cb17f59b189f65c2a6ac24e23731cb0b3ed9109fc109202",codec.digest(value));
        SourcePackage changed=new SourcePackage(1,value.packageId(),value.sourceSystemId(),value.sourceRunId(),value.outcomeRevisionId(),"2026-10-12T00:00:00Z",value.content(),value.redactionConfirmedAt(),value.contentSha256());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->codec.validate(changed));
    }
    /** 不信任来源文件携带的身份扩展、重复属性或执行字段。 */
    @Test void rejectsUnknownDuplicateAndOversizedInput() throws Exception {
        String valid=Files.readString(Path.of("contracts/source-snapshot/v1/example.json"));
        assertThrows(IllegalArgumentException.class,()->json.decode(valid.replaceFirst("\\{","{\"actorId\":\"forged\","),SourcePackage.class));
        assertThrows(IllegalArgumentException.class,()->json.decode(valid.replaceFirst("\\{","{\"schemaVersion\":1,"),SourcePackage.class));
        assertThrows(IllegalArgumentException.class,()->json.decode(valid.replace("UNCONFIRMED","OTHER"),SourcePackage.class));
        assertThrows(IllegalArgumentException.class,()->json.decode(valid.replace("合成协议样例，不是真实业务数据。","x".repeat(1001)),SourcePackage.class));
        assertThrows(IllegalArgumentException.class,()->json.decode(valid+"{}",SourcePackage.class));
    }
}
