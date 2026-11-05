package com.vibestory.agent;

import com.vibestory.common.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class SkillDirectoryTest {
    @TempDir Path root;

    @Test void discoversNewFoldersAndDirectEditsWithoutJavaRegistration() throws Exception {
        var store = new SkillDirectory(root.toString());
        assertThat(store.list(1,"SCRIPT")).isEmpty();
        Files.createDirectories(root.resolve("my-script"));
        Files.writeString(root.resolve("my-script/SKILL.md"),document("my-script","SCRIPT","原始规则"));
        var first = store.read(1,"my-script");
        assertThat(store.list(1,"SCRIPT")).extracting(SkillDirectory.Entry::id).containsExactly("my-script");
        Files.writeString(root.resolve("my-script/SKILL.md"),document("my-script","SCRIPT","编辑器修改"));
        assertThat(store.read(1,"my-script").content()).isEqualTo("编辑器修改");
        assertThat(store.read(1,"my-script").version()).isNotEqualTo(first.version());
    }

    @Test void personalOverridesUseTheSameFilesAndRejectConcurrentRevisions() throws Exception {
        Files.createDirectory(root.resolve("script"));
        Files.writeString(root.resolve("script/SKILL.md"),document("script","SCRIPT","内置规则"));
        var store = new SkillDirectory(root.toString());
        var builtin = store.read(1,"script");
        String changed = document("script","SCRIPT","个人规则");
        var saved = store.save(1,"script",builtin.version(),changed);
        assertThat(Files.readString(root.resolve(saved.location()))).isEqualTo(changed);
        assertThat(store.read(2,"script").content()).isEqualTo("内置规则");
        assertThat(store.save(1,"script",builtin.version(),changed).version()).isEqualTo(saved.version());
        assertThatThrownBy(() -> store.save(1,"script",builtin.version(),document("script","SCRIPT","覆盖别人修改")))
            .isInstanceOf(ApiException.class).hasMessageContaining("已变化");
    }

    @Test void rejectsTraversalSymbolicLinksAndInvalidMetadata() throws Exception {
        var store = new SkillDirectory(root.toString());
        assertThatThrownBy(() -> store.save(1,"../escape","",document("escape","SCRIPT","正文"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store.save(1,"bad","",document("bad","ROOT","正文"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store.save(1,"bad","","---\nname: bad\nname: bad\ndescription: test\nroles: [SCRIPT]\n---\n正文"))
            .isInstanceOf(ApiException.class);
        var outside = Files.createTempDirectory(root.getParent(),"skill-outside-");
        try {
            Files.createSymbolicLink(root.resolve("users"),outside);
            assertThatThrownBy(() -> store.save(1,"escape","",document("escape","SCRIPT","正文")))
                .isInstanceOf(ApiException.class).hasMessageContaining("符号链接");
            assertThat(Files.list(outside).toList()).isEmpty();
        } finally { Files.deleteIfExists(root.resolve("users")); Files.deleteIfExists(outside); }
    }

    private String document(String id,String role,String content) {
        return "---\nname: "+id+"\ndescription: 测试创作规范\nroles: ["+role+"]\n---\n\n"+content+"\n";
    }
}
