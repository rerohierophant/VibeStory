package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.dispatch.TaskNotification;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskNotificationContractTest {
    @Test void sharedNotificationSchemaMatchesSerializedFieldsVersionAndAllowedTypes() throws Exception {
        var json=new ObjectMapper();
        var schema=json.readTree(Files.readString(Path.of(System.getProperty("vibestory.contracts-dir"),"task-notification.v1.schema.json")));
        var event=new TaskNotification(1,UUID.randomUUID(),1,1,"VIDEO",2,3,4L);
        var payload=json.valueToTree(event);var fields=new HashSet<String>();payload.fieldNames().forEachRemaining(fields::add);
        var documented=new HashSet<String>();schema.path("properties").fieldNames().forEachRemaining(documented::add);
        assertThat(fields).isEqualTo(documented);assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(payload.path("schemaVersion").asInt()).isEqualTo(schema.path("properties").path("schemaVersion").path("const").asInt());
        for(var type:schema.path("properties").path("type").path("enum"))
            new TaskNotification(1,event.eventId(),1,1,type.asText(),2,3,null).validate();
        for(var required:schema.path("required")) assertThat(payload.hasNonNull(required.asText())).isTrue();
        assertThatThrownBy(()->new TaskNotification(2,event.eventId(),1,1,"VIDEO",2,3,null).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new TaskNotification(1,event.eventId(),1,1,"OTHER",2,3,null).validate()).isInstanceOf(IllegalArgumentException.class);
    }
}
