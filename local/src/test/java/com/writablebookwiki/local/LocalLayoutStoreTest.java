package com.writablebookwiki.local;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLayoutStoreTest {
    @TempDir Path root;
    @Test void missingFileUsesDefaultsWithoutWriting() throws Exception {
        Path file=root.resolve("layout.properties");
        assertEquals(LocalLayoutStore.DEFAULT,new LocalLayoutStore(file).load());
        assertFalse(Files.exists(file));
    }
    @Test void exactWidthsAndVisibilitySurviveNewStoreAndNoTempRemains() throws Exception {
        Path file=root.resolve("nested/layout.properties");
        var settings=new LocalLayoutStore.Settings(false,true,150,80);
        new LocalLayoutStore(file).save(settings);
        assertEquals(settings,new LocalLayoutStore(file).load());
        try(var files=Files.list(file.getParent())) { assertEquals(1,files.count()); }
    }
    @Test void malformedFieldsFallBackIndependentlyWithoutOverwriting() throws Exception {
        Path file=root.resolve("layout.properties");
        String value="directory_visible=no\nannotation_visible=false\ndirectory_width=999\nannotation_width=90\n";
        Files.writeString(file,value);
        assertEquals(new LocalLayoutStore.Settings(true,false,96,90),new LocalLayoutStore(file).load());
        assertEquals(value,Files.readString(file));
    }
    @Test void invalidWidthsCannotReplaceExistingFile() throws Exception {
        var store=new LocalLayoutStore(root.resolve("layout.properties"));store.save(LocalLayoutStore.DEFAULT);
        assertThrows(IllegalArgumentException.class,()->new LocalLayoutStore.Settings(true,true,0,100));
        assertEquals(LocalLayoutStore.DEFAULT,store.load());
    }
}
