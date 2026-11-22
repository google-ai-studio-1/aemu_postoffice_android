package com.example

import com.example.core.adhocctl.AdhocctlPackets
import com.example.core.adhocctl.AdhocctlServer
import com.example.core.adhocctl.GameDb
import com.example.core.adhocctl.GameDbCodec
import com.example.core.common.ByteKey
import com.example.core.common.ServerConfig
import com.example.core.common.ServerConfigCodec
import com.example.core.common.readBeUInt16
import com.example.core.common.writeLeInt32
import com.example.core.common.writeLeUInt16
import com.example.core.enet.EnetConstants
import com.example.core.relay.RelayPackets
import com.example.core.relay.RelayServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class PostOfficeCoreTest {

    @Test
    fun configMergeHotReloadVsRestartRequired() {
        val current = ServerConfig()
        val incoming = current.copy(
            port = 28000,
            strictMode = false,
            metadataName = "Updated Server",
            maxNumSessionsPerMac = 32
        )
        val merged = ServerConfigCodec.mergeConfig(current, incoming)
        assertTrue(merged.runtimeChanged)
        assertEquals(current.port, merged.merged.port)
        assertEquals(current.strictMode, merged.merged.strictMode)
        assertEquals("Updated Server", merged.merged.metadataName)
        assertEquals(32, merged.merged.maxNumSessionsPerMac)
        assertTrue(merged.restartRequiredKeys.contains("port"))
        assertTrue(merged.restartRequiredKeys.contains("strict_mode"))
    }

    @Test
    fun gameDbEqualityComparison() {
        val dbA = GameDb(
            crosslinks = mapOf("ULJM05800" to "ULUS10566"),
            names = mapOf("ULUS10566" to "Monster Hunter Portable 3rd"),
            codQuirkGames = setOf("ULUS10218")
        )
        val dbB = GameDb(
            crosslinks = mapOf("ULJM05800" to "ULUS10566"),
            names = mapOf("ULUS10566" to "Monster Hunter Portable 3rd"),
            codQuirkGames = setOf("ULUS10218")
        )
        val dbC = dbB.copy(codQuirkGames = emptySet())
        assertTrue(GameDbCodec.gameDbSame(dbA, dbB))
        assertFalse(GameDbCodec.gameDbSame(dbA, dbC))
    }

    @Test
    fun relayAndEnetPacketCodecs() {
        val mac = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val initBuf = buildPdpInitPacket(mac, 3000)
        assertEquals(RelayPackets.SIZE_INIT, initBuf.size)
        val decodedInit = RelayPackets.parseInit(initBuf, 0)
        assertEquals(RelayPackets.INIT_PDP, decodedInit.initType)
        assertArrayEquals(mac, decodedInit.srcAddr)
        assertEquals(3000, decodedInit.sport)

        val payload = "TEST".toByteArray(Charsets.US_ASCII)
        val pdpPacket = RelayPackets.buildPdpPacket(mac, 3000, payload, 0, payload.size)
        assertEquals(RelayPackets.SIZE_PDP + payload.size, pdpPacket.size)
        val decodedPdp = RelayPackets.parsePdpHeader(pdpPacket, 0)
        assertArrayEquals(mac, decodedPdp.addr)
        assertEquals(3000, decodedPdp.port)
        assertEquals(payload.size.toLong(), decodedPdp.size)

        val headerBytes = ByteArray(EnetConstants.HEADER_SIZE_WITH_TIME)
        val written = EnetConstants.encodePacketHeader(headerBytes, 0x0012, 2, 0x1234)
        assertEquals(EnetConstants.HEADER_SIZE_WITH_TIME, written)
        val headerWord = readBeUInt16(headerBytes, 0)
        assertEquals(0x0012, headerWord and EnetConstants.PROTOCOL_MAXIMUM_PEER_ID)
        assertEquals(2, (headerWord and EnetConstants.HEADER_SESSION_MASK) ushr EnetConstants.HEADER_SESSION_SHIFT)
        assertTrue((headerWord and EnetConstants.HEADER_FLAG_SENT_TIME) != 0)
        assertEquals(0x1234, readBeUInt16(headerBytes, 2))
    }

    @Test
    fun loopbackAdhocctlCrosslinkCodQuirkAndRelayPdpPtpIntegration() {
        val adhocPort = allocateFreePort()
        val relayPort = allocateFreePort()
        val testConfig = ServerConfig(
            ipAddr = "127.0.0.1",
            enableEnet = false,
            port = relayPort,
            numThreads = 0,
            strictMode = false,
            enableAdhocctl = true,
            adhocctlPort = adhocPort,
            adhocctlNumThreads = 1,
            adhocctlRelayOnly = true,
            metadataName = "Unit Test Server"
        )
        val gameDb = GameDb(
            crosslinks = mapOf("ULJM05800" to "ULUS10566"),
            names = mapOf("ULUS10566" to "Test PSP Game"),
            codQuirkGames = setOf("ULUS10566")
        )

        val adhocServer = AdhocctlServer(testConfig, gameDb)
        val relayServer = RelayServer(testConfig)

        try {
            assertFalse(adhocServer.isDead())
            assertFalse(relayServer.isDead())

            val macA = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x01)
            val macB = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x02)

            Socket("127.0.0.1", adhocPort).use { clientA ->
                clientA.soTimeout = 2000
                clientA.getOutputStream().write(buildLoginPacket(macA, "PlayerA", "ULJM05800"))
                clientA.getOutputStream().flush()

                val connectPacket = ByteArray(AdhocctlPackets.SIZE_CONNECT_C2S)
                connectPacket[0] = AdhocctlPackets.OPCODE_CONNECT.toByte()
                val roomBytes = "ROOM1".toByteArray(Charsets.US_ASCII)
                System.arraycopy(roomBytes, 0, connectPacket, 1, roomBytes.size)
                clientA.getOutputStream().write(connectPacket)
                clientA.getOutputStream().flush()

                repeat(20) {
                    adhocServer.pump()
                    Thread.sleep(10)
                }

                val snap = adhocServer.getSnapshot()
                assertEquals(1, snap.clients.size)
                assertTrue(snap.clients.containsKey(ByteKey(macA)))
                assertTrue(snap.games.containsKey("ULUS10566"))
                val groups = snap.games["ULUS10566"]!!.groups
                assertTrue(groups.values.any { it.name == "cod_quirk_fix_applied" })
            }

            Socket("127.0.0.1", relayPort).use { pdpA ->
                Socket("127.0.0.1", relayPort).use { pdpB ->
                    pdpA.soTimeout = 2000
                    pdpB.soTimeout = 2000

                    pdpA.getOutputStream().write(buildPdpInitPacket(macA, 4000))
                    pdpA.getOutputStream().flush()
                    pdpB.getOutputStream().write(buildPdpInitPacket(macB, 4000))
                    pdpB.getOutputStream().flush()

                    repeat(15) {
                        relayServer.pump()
                        Thread.sleep(10)
                    }

                    val payload = "HELLO_PSP".toByteArray(Charsets.US_ASCII)
                    val frame = RelayPackets.buildPdpPacket(macB, 4000, payload, 0, payload.size)
                    pdpA.getOutputStream().write(frame)
                    pdpA.getOutputStream().flush()

                    repeat(15) {
                        relayServer.pump()
                        Thread.sleep(10)
                    }

                    val recvBuf = ByteArray(RelayPackets.SIZE_PDP + payload.size)
                    var readTotal = 0
                    val inp = pdpB.getInputStream()
                    while (readTotal < recvBuf.size) {
                        val r = inp.read(recvBuf, readTotal, recvBuf.size - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    assertEquals(recvBuf.size, readTotal)
                    val recvHeader = RelayPackets.parsePdpHeader(recvBuf, 0)
                    assertArrayEquals(macA, recvHeader.addr)
                    assertEquals(4000, recvHeader.port)
                    assertEquals(payload.size.toLong(), recvHeader.size)
                    assertArrayEquals(payload, recvBuf.copyOfRange(RelayPackets.SIZE_PDP, recvBuf.size))
                }
            }
        } finally {
            relayServer.shutdown()
            adhocServer.shutdown()
        }
    }

    private fun buildPdpInitPacket(srcMac: ByteArray, srcPort: Int): ByteArray {
        val out = ByteArray(RelayPackets.SIZE_INIT)
        writeLeInt32(out, 0, RelayPackets.INIT_PDP)
        System.arraycopy(srcMac, 0, out, 4, 6)
        writeLeUInt16(out, 12, srcPort)
        return out
    }

    private fun buildLoginPacket(mac: ByteArray, nick: String, gameCode: String): ByteArray {
        val buf = ByteArray(AdhocctlPackets.SIZE_LOGIN_C2S)
        buf[0] = AdhocctlPackets.OPCODE_LOGIN.toByte()
        System.arraycopy(mac, 0, buf, 1, AdhocctlPackets.ETHER_ADDR_LEN)
        val nickBytes = nick.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nickBytes, 0, buf, 1 + AdhocctlPackets.ETHER_ADDR_LEN, nickBytes.size)
        val gameBytes = gameCode.toByteArray(Charsets.US_ASCII)
        System.arraycopy(
            gameBytes,
            0,
            buf,
            1 + AdhocctlPackets.ETHER_ADDR_LEN + AdhocctlPackets.NICKNAME_LEN,
            gameBytes.size
        )
        return buf
    }

    private fun allocateFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }
}
