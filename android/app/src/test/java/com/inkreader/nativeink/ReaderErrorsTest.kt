package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class ReaderErrorsTest {
    @Test fun expiredSharedFileHasActionableChineseWithoutInternalUri() {
        val value=ReaderErrors.message("No content provider: content://com.tencent.mm.external.fileprovider/private",false)
        assertTrue(value.contains("本地文件夹"));assertFalse(value.contains("content://"));assertFalse(value.contains("provider"))
    }
    @Test fun expiredSharedFileHasActionableEnglishWithoutInternalUri() {
        val value=ReaderErrors.message("Permission Denial: content://private/file.epub",true)
        assertTrue(value.contains("local folder"));assertFalse(value.contains("content://"))
    }
    @Test fun corruptArchiveReportsAUserActionInsteadOfTheZipParserMessage() {
        val value=ReaderErrors.message("zip END header not found",false)
        assertTrue(value.contains("重新下载"));assertFalse(value.contains("END"))
    }
}
