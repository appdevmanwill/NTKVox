package com.example.storage

import android.util.Log
import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.VbrMode
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.FileOutputStream

/**
 * Pure-Java MP3 chunk writer powered by Jump3r's low-level LAME engine (zero C/C++ NDK,
 * zero desktop javax.sound dependency).
 *
 * Streams 16-bit mono PCM (scaled into 32-bit left-aligned integers as required by
 * [Lame.lame_encode_buffer_int]) into standard 64 kbps CBR MPEG-2 Layer III frames with
 * periodic `fsync()` durability.
 */
class Mp3ChunkWriter(
    private val bitrateKbps: Int = 64
) : AudioChunkWriter {

    override val fileExtension: String = "mp3"

    private var lame: Lame? = null
    private var gfp: LameGlobalFlags? = null
    private var outputStream: FileOutputStream? = null
    private var outputFile: File? = null
    private var sampleRate: Int = 16_000
    private var channels: Int = 1
    private var totalSamplesWritten: Long = 0L
    private var totalBytesWritten: Long = 0L

    private var pcmIntScratch = IntArray(4096)
    private var mp3OutScratch = ByteArray(16_384)

    override val durationMillis: Long
        get() = if (sampleRate > 0) (totalSamplesWritten * 1000L) / sampleRate else 0L

    override val bytesWritten: Long
        get() = totalBytesWritten

    override fun open(outputFile: File, sampleRate: Int, channels: Int) {
        this.outputFile = outputFile
        this.sampleRate = sampleRate
        this.channels = channels
        this.totalSamplesWritten = 0L
        this.totalBytesWritten = 0L

        outputFile.parentFile?.mkdirs()

        // Wire pure-Java LAME modules directly without javax.sound
        val lameInst = Lame()
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        // Instantiate BRHist to match LAME module lifecycle cleanly
        BRHist()

        lameInst.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lameInst)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lameInst.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lameInst, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        val flags = lameInst.lame_init()
        flags.num_channels = channels
        flags.in_samplerate = sampleRate
        flags.out_samplerate = sampleRate
        flags.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        flags.VBR = VbrMode.vbr_off
        flags.brate = bitrateKbps
        flags.quality = 5 // Balanced speed & voice clarity for real-time mobile encoding
        flags.bWriteVbrTag = false
        flags.write_id3tag_automatic = false

        val rc = lameInst.lame_init_params(flags)
        if (rc < 0) {
            throw IllegalStateException("Jump3r lame_init_params failed with code $rc")
        }

        this.lame = lameInst
        this.gfp = flags
        this.outputStream = FileOutputStream(outputFile)
    }

    override fun writePcm(samples: ShortArray, offset: Int, length: Int) {
        val encoder = lame ?: return
        val flags = gfp ?: return
        val fos = outputStream ?: return
        if (length <= 0) return

        val sampleFrames = length / channels
        if (sampleFrames <= 0) return

        if (pcmIntScratch.size < sampleFrames) {
            pcmIntScratch = IntArray(sampleFrames)
        }
        val neededOut = (7200 + (sampleFrames * 5) / 4).coerceAtLeast(8192)
        if (mp3OutScratch.size < neededOut) {
            mp3OutScratch = ByteArray(neededOut)
        }

        // Jump3r lame_encode_buffer_int expects 16-bit PCM left-shifted to 32-bit MSB
        for (i in 0 until sampleFrames) {
            pcmIntScratch[i] = samples[offset + i * channels].toInt() shl 16
        }

        val encoded = encoder.lame_encode_buffer_int(
            flags,
            pcmIntScratch,
            pcmIntScratch,
            sampleFrames,
            mp3OutScratch,
            0,
            mp3OutScratch.size
        )
        if (encoded > 0) {
            fos.write(mp3OutScratch, 0, encoded)
            totalBytesWritten += encoded
        }
        totalSamplesWritten += sampleFrames.toLong()
    }

    override fun syncToDisk() {
        runCatching {
            outputStream?.flush()
            outputStream?.fd?.sync()
        }
    }

    override fun closeAndFinalize(): ChunkFinalizeResult {
        val encoder = lame
        val flags = gfp
        val fos = outputStream
        val target = outputFile ?: File("")

        if (encoder != null && flags != null && fos != null) {
            try {
                val flushed = encoder.lame_encode_flush(flags, mp3OutScratch, 0, mp3OutScratch.size)
                if (flushed > 0) {
                    fos.write(mp3OutScratch, 0, flushed)
                    totalBytesWritten += flushed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing MP3 encoder", e)
            } finally {
                runCatching { encoder.lame_close(flags) }
            }
        }

        lame = null
        gfp = null

        fos?.let {
            runCatching { it.flush() }
            runCatching { it.fd.sync() }
            runCatching { it.close() }
        }
        outputStream = null

        return ChunkFinalizeResult(
            file = target,
            actualExtension = fileExtension,
            durationMillis = durationMillis,
            sizeBytes = if (target.exists()) target.length() else totalBytesWritten
        )
    }

    companion object {
        private const val TAG = "Mp3ChunkWriter"
    }
}
