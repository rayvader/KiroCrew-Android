package io.github.rayvader.kirocrew.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertChannelsTest {

    private fun note(extra: String) =
        AlertFeed.parseNoteJson("""{"ts":"2026-11-07T10:00:00+00:00","title":"Test note"$extra}""")!!

    @Test
    fun aBotsNoteGoesToItsOwnChannel() {
        val r = AlertChannels.route("system.member", AlertMember("test-butler", "Test Butler"))
        assertEquals(AlertRoute("bot_test-butler", ChannelGroup.BOTS, "Test Butler"), r)
    }

    @Test
    fun theMemberWinsOverTheChannel() {
        // A Bot posting an agent message still lands in that Bot's own channel.
        assertEquals("bot_demo-bot", AlertChannels.route("system.agent", AlertMember("demo-bot", "Demo")).id)
        assertEquals("bot_demo-bot", AlertChannels.route(null, AlertMember("demo-bot", "Demo")).id)
    }

    @Test
    fun kindsWithoutABot() {
        assertEquals(AlertRoute("news", ChannelGroup.KIRO_CREW), AlertChannels.route("system.agent", null))
        assertEquals(AlertRoute("watches", ChannelGroup.KIRO_CREW), AlertChannels.route("system.monitor", null))
        assertEquals("chat", AlertChannels.route("chat.completion", null).id)
        assertEquals("chat", AlertChannels.route("session.pool_size", null).id)
        assertEquals("watches", AlertChannels.route(" System.Monitor ", null).id)
    }

    @Test
    fun unknownOrMissingFallsBackToDashboardAlerts() {
        assertEquals(AlertRoute("alerts", ChannelGroup.KIRO_CREW), AlertChannels.route("system.approval", null))
        assertEquals("alerts", AlertChannels.route("something.new", null).id)
        assertEquals("alerts", AlertChannels.route(null, null).id)
        assertEquals("alerts", AlertChannels.route("", null).id)
        // A member with no usable slug is treated as no member.
        assertEquals("alerts", AlertChannels.route(null, AlertMember("  ", "Ghost")).id)
        assertEquals("alerts", AlertChannels.route(null, AlertMember("***", "Ghost")).id)
    }

    @Test
    fun channelNamesComeFromTheBotsName() {
        assertEquals("Test Butler", AlertChannels.displayName("Test Butler", "test-butler"))
        assertEquals("Demo", AlertChannels.displayName("  Demo  ", "demo-bot"))
        assertEquals("Mr. Demo", AlertChannels.displayName("Mr.  Demo", "mr-demo"))
        // The feed often sends the slug as the name: turned into words.
        assertEquals("Trip Helper", AlertChannels.displayName("trip-helper", "trip-helper"))
        assertEquals("Money Mate Two", AlertChannels.displayName("money_mate-two", "money-mate-two"))
        // No name: from the slug.
        assertEquals("Demo Bot", AlertChannels.displayName(null, "demo-bot"))
        assertEquals("Demo Bot", AlertChannels.displayName("", "demo-bot"))
        // A one-word slug-like name gets a capital; a name with capitals is kept as given.
        assertEquals("Demo", AlertChannels.displayName("demo", "demo"))
        assertEquals("deMo", AlertChannels.displayName("deMo", "demo"))
        // Long names are cut for Android's channel list.
        assertEquals(40, AlertChannels.displayName("A".repeat(80), "a").length)
    }

    @Test
    fun aRenamedBotKeepsItsChannelId() {
        val before = AlertChannels.route("system.member", AlertMember("demo-bot", "Demo"))
        val after = AlertChannels.route("system.member", AlertMember("demo-bot", "Demo Renamed"))
        assertEquals(before.id, after.id)
        assertEquals("Demo Renamed", after.botName)
    }

    @Test
    fun slugsAreSafeChannelIds() {
        assertEquals("demo-bot", AlertChannels.cleanSlug(" Demo-Bot "))
        assertEquals("a_b_c", AlertChannels.cleanSlug("a b/c"))
        assertEquals(60, AlertChannels.cleanSlug("x".repeat(100)).length)
    }

    @Test
    fun theFeedsChannelAndMemberAreRead() {
        val a = note(""","channel":"system.member","member":{"slug":"test-butler","name":"Test Butler"}""")
        assertEquals("system.member", a.channel)
        assertEquals(AlertMember("test-butler", "Test Butler"), a.member)
        assertEquals("bot_test-butler", a.route.id)
        assertEquals("Test Butler", a.route.botName)
    }

    @Test
    fun anOldFeedWithoutTheFieldsStillWorks() {
        val a = note(""","body":"hi","priority":"normal"""")
        assertNull(a.channel)
        assertNull(a.member)
        assertEquals("alerts", a.route.id)
        assertEquals("Test note", a.title)
    }

    @Test
    fun oddMemberShapesAreIgnored() {
        assertNull(note(""","member":null""").member)
        assertNull(note(""","member":"test-butler"""").member)
        assertNull(note(""","member":{"name":"No Slug"}""").member)
        assertEquals(AlertMember("demo-bot", ""), note(""","member":{"slug":"demo-bot"}""").member)
        assertEquals("Demo Bot", note(""","member":{"slug":"demo-bot"}""").route.botName)
        assertNull(note(""","channel":null""").channel)
    }

    @Test
    fun aFeedListParsesTheNewFields() {
        val feed = """{"notifications":[
            {"ts":"2026-11-07T10:00:00Z","title":"A","channel":"system.agent"},
            {"ts":"2026-11-07T10:01:00Z","title":"B","channel":"system.monitor"},
            {"ts":"2026-11-07T10:02:00Z","title":"C","member":{"slug":"trip-helper","name":"trip-helper"}}
        ]}"""
        val routes = AlertFeed.parseFeed(feed)!!.map { it.route }
        assertEquals(listOf("news", "watches", "bot_trip-helper"), routes.map { it.id })
        assertEquals("Trip Helper", routes[2].botName)
    }
}
