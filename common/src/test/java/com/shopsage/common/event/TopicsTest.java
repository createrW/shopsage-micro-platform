package com.shopsage.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TopicsTest {

    // Surefire runs with the module directory (common/) as working directory, so ".." is the repo root.
    private static final Path REPO_ROOT = Path.of("..");
    private static final Pattern BASE_TOPICS = Pattern.compile("(?m)^BASE_TOPICS=\"([^\"]*)\"");

    @Test
    void allContainsTheTenSagaTopicsWithoutDuplicates() {
        assertThat(Topics.all()).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void everyTopicFollowsTheDomainDotEventConvention() {
        assertThat(Topics.all()).allMatch(topic -> topic.matches("^[a-z]+\\.[a-z]+$"));
    }

    @Test
    void everyTopicConstantIsListedInAll() throws IllegalAccessException {
        // Guards against adding a constant but forgetting it in all() (and thus in topic creation checks).
        List<Field> topicConstants = Arrays.stream(Topics.class.getDeclaredFields())
                .filter(f -> Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers()))
                .filter(f -> f.getType() == String.class && !f.getName().equals("DLT_SUFFIX"))
                .toList();
        assertThat(topicConstants).hasSize(10);
        for (Field field : topicConstants) {
            assertThat(Topics.all()).contains((String) field.get(null));
        }
    }

    /**
     * Topics.all(), TOPICS of kafka-init in docker-compose.yml (creates them; the broker has auto-create off) and
     * BASE_TOPICS in scripts/dev-up.sh (verifies them) are three copies of one list: renaming or adding a topic in
     * one place only would surface at runtime as a missing topic, so the build catches it here.
     */
    @Test
    void topicsAllAndComposeKafkaInitAndDevUpScriptListsStayInSync() throws IOException {
        assertThat(composeKafkaInitTopics())
                .as("docker-compose.yml services.kafka-init.environment.TOPICS must equal Topics.all() "
                        + "(Topics.all(), compose TOPICS and dev-up.sh BASE_TOPICS must stay in sync)")
                .containsExactlyInAnyOrderElementsOf(Topics.all());

        String devUp = Files.readString(REPO_ROOT.resolve("scripts/dev-up.sh"));
        Matcher matcher = BASE_TOPICS.matcher(devUp);
        assertThat(matcher.find()).as("scripts/dev-up.sh defines BASE_TOPICS=\"...\"").isTrue();
        assertThat(List.of(matcher.group(1).trim().split("\\s+")))
                .as("scripts/dev-up.sh BASE_TOPICS must contain every Topics.all() name "
                        + "(Topics.all(), compose TOPICS and dev-up.sh BASE_TOPICS must stay in sync)")
                .containsExactlyInAnyOrderElementsOf(Topics.all());
    }

    @SuppressWarnings("unchecked")
    private static List<String> composeKafkaInitTopics() throws IOException {
        Map<String, Object> compose;
        try (Reader reader = Files.newBufferedReader(REPO_ROOT.resolve("docker-compose.yml"))) {
            compose = new Yaml().load(reader);
        }
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        Map<String, Object> kafkaInit = (Map<String, Object>) services.get("kafka-init");
        Map<String, Object> environment = (Map<String, Object>) kafkaInit.get("environment");
        String topics = String.valueOf(environment.get("TOPICS"));
        return List.of(topics.trim().split("\\s+"));
    }

    @Test
    void dltOfAppendsSpringKafkaDefaultSuffix() {
        assertThat(Topics.dltOf("order.created")).isEqualTo("order.created.DLT");
    }

    @Test
    void dltOfRejectsDeadLetterTopics() {
        assertThatIllegalArgumentException().isThrownBy(() -> Topics.dltOf("order.created.DLT"));
    }
}
