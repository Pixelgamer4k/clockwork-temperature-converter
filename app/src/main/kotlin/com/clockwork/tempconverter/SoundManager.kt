package com.clockwork.tempconverter

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.sin

class SoundManager(context: Context) {
    private var soundPool: SoundPool? = null
    private var soundId: Int = -1
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private val tempFile: File

    init {
        tempFile = File(context.cacheDir, "mechanical_tick.wav")
        generateAndLoadTickSound()
    }

    private fun generateAndLoadTickSound() {
        scope.launch {
            val sampleRate = 44100
            val durationMs = 25
            val numSamples = sampleRate * durationMs / 1000
            val buffer = ShortArray(numSamples)

            // Generate a high-quality metallic tick sound (rapid decay)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                // Exponential decay envelope (extremely fast, mimics metal-on-metal strike)
                val envelope = exp(-t * 260.0)

                // Combination of metallic high frequency and woody body click
                val sine1 = sin(2.0 * Math.PI * 3200.0 * t) // High metal ping
                val sine2 = sin(2.0 * Math.PI * 650.0 * t)  // Low tooth strike
                val noise = (Math.random() * 2.0 - 1.0) * 0.35 // Transient impact noise

                val sample = (sine1 * 0.45 + sine2 * 0.25 + noise * 0.30) * envelope
                // Coerce in range and convert to 16-bit PCM Short
                buffer[i] = (sample * 32767.0 * 0.35).toInt().coerceIn(-32768, 32767).toShort()
            }

            try {
                // Write PCM data as a standard WAV file
                writeWavFile(tempFile, sampleRate, buffer)

                // Build SoundPool
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                soundPool = SoundPool.Builder()
                    .setMaxStreams(3)
                    .setAudioAttributes(attrs)
                    .build().apply {
                        setOnLoadCompleteListener { _, sampleId, status ->
                            if (status == 0 && sampleId == soundId) {
                                isLoaded = true
                            }
                        }
                        soundId = load(tempFile.absolutePath, 1)
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun writeWavFile(file: File, sampleRate: Int, shortData: ShortArray) {
        val totalAudioLen = shortData.size * 2
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // fmt 
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // size of fmt chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // block align
        header[33] = 0
        header[34] = 16 // 16 bits
        header[35] = 0
        header[36] = 'd'.code.toByte() // data
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        FileOutputStream(file).use { out ->
            out.write(header)
            val byteBuf = ByteBuffer.allocate(shortData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in shortData) {
                byteBuf.putShort(s)
            }
            out.write(byteBuf.array())
        }
    }

    fun playTick() {
        if (!isLoaded) return
        scope.launch {
            try {
                soundPool?.play(soundId, 0.95f, 0.95f, 1, 0, 1.0f)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun release() {
        try {
            soundPool?.release()
            soundPool = null
            isLoaded = false
            if (tempFile.exists()) {
                tempFile.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
