package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class LegacyBookRepairTest {
    @Test fun printedContentsAndRepeatedHtmlTitlesDoNotBecomeTwoChapters() {
        val body="正文完整保留，既有标点也有完整句子。".repeat(8)
        val original="书名\nTable of Contents\n目录\n卷一　山川\n卷二　江海\n译者的话\n译者的话\n$body\n卷一　山川\n卷一　山川\n第一层面　观察\n$body"
        val lines=LegacyBookRepair.lines(original)
        assertEquals(listOf("书名","译者的话",body,"卷一　山川","第一层面　观察",body),lines)
    }
    @Test fun repeatedCompleteProseIsNeverRemovedAsATitle() {
        val sentence="这是一句有意重复的正文。"
        assertEquals(listOf("书名",sentence,sentence),LegacyBookRepair.lines("书名\n$sentence\n$sentence"))
    }
}
