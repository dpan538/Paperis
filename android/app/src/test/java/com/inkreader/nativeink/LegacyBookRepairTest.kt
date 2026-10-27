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
    @Test fun oldCopyrightFieldsAreSeparateAndAuthorLabelStaysWhole() {
        val value="书名\n\n版权信息原典书坊 书名作　者：某作者\n\n出版社：示例出版社"
        assertEquals(listOf("书名","版权信息","原典书坊 书名","作者：某作者","出版社：示例出版社"),LegacyBookRepair.lines(value))
    }
    @Test fun repeatedFileTitlesAndCollapsedFrontMatterAreRepaired() {
        val value="Dedication\n\nA complete dedication sentence.\n\nSample-final.html A Note on Natural History Natural history begins with careful reading.\n\nSample-final.html\n\nCHAPTER I\n\nThe first paragraph stays complete."
        val result=LegacyBookRepair.lines(value)
        assertFalse(result.any {it.contains(".html")})
        assertTrue(result.contains("A Note on Natural History"));assertTrue(result.contains("Natural history begins with careful reading."))
    }
    @Test fun printedContentsAreRemovedEvenWhenTheirLabelWasAlreadyLost() {
        val value="前言\n\n正文完整保留。\n\n第二部 远方\n\n13. 一条路................................(45)\n\n14. 一座山................................(50)\n\n第一章 一条路\n\n这段正文有省略号......也必须保留。"
        val result=LegacyBookRepair.lines(value)
        assertFalse(result.any {it.contains("(45)")||it.contains("(50)")||it=="第二部 远方"})
        assertTrue(result.contains("这段正文有省略号......也必须保留。"))
    }
    @Test fun wrappedContentsLeaderAndPageNumberDoNotLeaveAFalsePartHeading() {
        val value="正文。\n第二部 远方\n13. 一条路\n...........................\n(45) 14. 一座山...........................(50)\n第一章 一条路\n章节正文。"
        assertEquals(listOf("正文。","第一章 一条路","章节正文。"),LegacyBookRepair.lines(value))
    }
    @Test fun technicalFilenamesAndAnIsolatedLeaderExampleRemainUntouched() {
        val value="index.html\nEdit the title in your editor.\nindex.html\nSave the file in this folder.\nA printed sample ........ (12)\nThe explanation remains complete."
        assertEquals(value.lines(),LegacyBookRepair.lines(value))
    }
    @Test fun repeatedRefrainsWithoutPunctuationAreNotDeletedAsHeadings() {
        val value="夜色静下来\n夜色静下来\nNight Falls\nNight Falls"
        assertEquals(value.lines(),LegacyBookRepair.lines(value))
    }
    @Test fun metadataLabelsInsideProseAreNotTurnedIntoFields() {
        val value="他说：“作　者：这个词只是正文讨论的标签。”"
        assertEquals(listOf(value),LegacyBookRepair.lines(value))
    }
    @Test fun EnglishCopyrightFieldsHaveTheSameBoundaryPolicy() {
        val value="Copyright Information\nAuthor: Ada Reader Publisher: Example Press"
        assertEquals(listOf("Copyright Information","Author: Ada Reader","Publisher: Example Press"),LegacyBookRepair.lines(value))
    }
    @Test fun localBoundariesDistinguishSoftLinesFromSeparateParagraphs() {
        val value="书名\n\n第一段的第一行\n第一段的第二行\n\n第二段保持独立"
        val pieces=LegacyBookRepair.pieces(value)
        assertEquals(listOf(false,true,false,true),pieces.map {it.hardBreakBefore})
        assertEquals(value.indexOf("第二段"),pieces.last().sourceOffset)
        assertFalse(LegacyBookRepair.probablePdfWrap("Night Falls","Night Falls"))
        assertTrue(LegacyBookRepair.probablePdfWrap("清晨醒来时，山还隐在窗外的薄雾中，屋子里很静", "只有远处的水声。"))
    }
}
