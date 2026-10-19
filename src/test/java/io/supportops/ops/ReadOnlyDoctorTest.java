package io.supportops.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证健康结构的拒绝边界；实际 HTTP 与真实 ES 另由隔离入口复验。 */
class ReadOnlyDoctorTest {
    /** 生成包含必要字段的健康协议数据，不连接任何服务。 */
    private ObjectNode health(String status) {
        ObjectNode node = new ObjectMapper().createObjectNode();
        node.put("cluster_name", "synthetic-cluster");
        node.put("status", status);
        node.put("timed_out", false);
        node.put("number_of_nodes", 1);
        node.put("number_of_data_nodes", 1);
        node.put("active_primary_shards", 1);
        node.put("active_shards", 1);
        return node;
    }

    /** ES 明确支持的正常状态才可以通过。 */
    @ParameterizedTest
    @ValueSource(strings = {"green", "yellow"})
    void acceptsSupportedStates(String status) {
        assertThat(ReadOnlyDoctor.healthyCluster(health(status))).isTrue();
    }

    /** 红色、空值、大小写漂移和未知状态不能被视为健康。 */
    @ParameterizedTest
    @ValueSource(strings = {"red", "", "GREEN", "unknown"})
    void rejectsOtherStates(String status) {
        assertThat(ReadOnlyDoctor.healthyCluster(health(status))).isFalse();
    }

    /** 任一必要字段缺失都不能由默认值补成健康响应。 */
    @ParameterizedTest
    @ValueSource(strings = {"cluster_name", "status", "timed_out", "number_of_nodes", "number_of_data_nodes", "active_primary_shards", "active_shards"})
    void rejectsMissingFields(String field) {
        ObjectNode node = health("green");
        node.remove(field);
        assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
    }

    /** 根结构错误、请求超时和不可能的节点数均拒绝。 */
    @Test
    void rejectsInvalidStructureAndTimeout() throws Exception {
        for (String source : new String[]{"{}", "null", "[]", "\"green\""}) {
            JsonNode node = new ObjectMapper().readTree(source);
            assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
        }
        ObjectNode node = health("green");
        node.put("timed_out", true);
        assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
        node.put("timed_out", false);
        node.put("number_of_nodes", 0);
        assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
        node.put("number_of_nodes", 1);
        node.put("number_of_data_nodes", 2);
        assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
        node.put("number_of_data_nodes", "1");
        assertThat(ReadOnlyDoctor.healthyCluster(node)).isFalse();
    }
}
