package com.varun.filemanager.training

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatasetFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun put(dir: String, name: String, text: String = "x") = File(tmp.root, "$dir/$name").apply { parentFile!!.mkdirs(); writeText(text) }

    @Test fun scanFindsOnlyPictureFoldersSortedByName() {
        put("sad_mouth", "b.jpg"); put("sad_mouth", "a.PNG"); put("sad_mouth", "labels.csv"); put("sad_mouth", ".nomedia")
        put("smiling", "1.heic")
        put("empty", "notes.txt")
        put(".hidden", "z.jpg")
        val found = DatasetFolders.scan(tmp.root)
        assertEquals(listOf("sad_mouth", "smiling"), found.map { it.name })
        assertEquals(listOf("a.PNG", "b.jpg"), found[0].files.map { it.name })
    }

    @Test fun signatureChangesWhenAPhotoIsAddedOrChanged() {
        put("a", "1.jpg", "one")
        val before = DatasetFolders.scan(tmp.root).single()
        assertEquals(before.signature, DatasetFolders.scan(tmp.root).single().signature)
        put("a", "2.jpg", "two")
        val added = DatasetFolders.scan(tmp.root).single()
        assertNotEquals(before.signature, added.signature)
        put("a", "2.jpg", "two but longer")
        assertNotEquals(added.signature, DatasetFolders.scan(tmp.root).single().signature)
    }

    @Test fun labelsIdsAndDescriptionComeFromTheFolder() {
        assertEquals("Sad mouth", DatasetFolders.label("sad_mouth"))
        assertEquals("ds-sad-mouth", DatasetFolders.categoryId("sad_mouth"))
        assertEquals("dataset:sad_mouth", DatasetFolders.source("sad_mouth"))
        put("x", "1.jpg"); put("x", "description.txt", "  faces that look unhappy \n")
        assertEquals("faces that look unhappy", DatasetFolders.scan(tmp.root).single().description)
    }
}
