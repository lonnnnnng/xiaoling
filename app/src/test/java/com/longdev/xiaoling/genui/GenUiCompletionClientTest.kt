package com.longdev.xiaoling.genui

import com.longdev.xiaoling.model.*
import com.longdev.xiaoling.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenUiCompletionClientTest {
    private fun event(type: String, field: String, value: Any) = JSONObject().put("type",type).put(field,value).toString()
    @Test fun streamCompletionCannotAuthorizeLaterFinalTextOrDelta() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            val completed=event("response.completed","response",JSONObject().put("status","completed").put("output_text","原完成正文"))
            for (late in listOf(event("response.output_text.done","text",genUiFixtureText()),event("response.output_text.delta","delta",genUiFixtureText()))) {
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $completed\n\ndata: $late\n\n"))
                val response=OpenAiCompatibleClient().sendMessage(ProviderRequestConfig(server.url("/v1").toString(),"fixture","fixture",
                    apiMode=ApiMode.RESPONSES,streamingEnabled=true,httpDebugLoggingEnabled=false),listOf(RequestMessage("user","本地夹具")))
                assertFalse(response.actionCompletionConfirmed)
            }
        } finally { server.shutdown() }
    }
    @Test fun partialEofAndLengthRemainReadonlyWhileNormalStopCanIssueLease() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            for (terminal in listOf("", "length", "stop")) {
                val delta=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("delta",JSONObject().put("content",genUiFixtureText()))))
                val end=if(terminal.isEmpty()) "" else "data: ${JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("finish_reason",terminal)))}\n\n"
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $delta\n\n$end"))
                val response=OpenAiCompatibleClient().sendMessage(ProviderRequestConfig(server.url("/v1").toString(),"fixture","fixture",
                    streamingEnabled=true,httpDebugLoggingEnabled=false),listOf(RequestMessage("user","本地夹具")))
                assertEquals(terminal=="stop",response.actionCompletionConfirmed); assertNotNull(GenUiPolicy.projectAssistantText(response.responseText))
            }
        } finally { server.shutdown() }
    }
    @Test fun terminalWithoutFinalBodyCannotAuthorizePreviouslyCachedCard() = runBlocking {
        val server=MockWebServer(); server.start()
        try {
            for (output in listOf("[]", """[{"type":"message","content":[{"type":"refusal","refusal":"拒绝"}]}]""")) {
                val before=event("response.output_text.done","text",genUiFixtureText())
                val end=event("response.completed","response",JSONObject().put("status","completed").put("output",org.json.JSONArray(output)))
                server.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("data: $before\n\ndata: $end\n\n"))
                val response=OpenAiCompatibleClient().sendMessage(ProviderRequestConfig(server.url("/v1").toString(),"fixture","fixture",
                    apiMode=ApiMode.RESPONSES,streamingEnabled=true,httpDebugLoggingEnabled=false),listOf(RequestMessage("user","本地夹具")))
                assertEquals(genUiFixtureText(),response.responseText); assertFalse(response.actionCompletionConfirmed)
            }
        } finally { server.shutdown() }
    }
}
