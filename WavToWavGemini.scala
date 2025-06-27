//> using scala "3.3.6"
//> using dep com.softwaremill.sttp.client4::fs2:4.0.9
//> using dep org.typelevel::cats-effect:3.6.1
//> using dep io.circe::circe-core:0.14.14
//> using dep io.circe::circe-generic:0.14.14
//> using dep io.circe::circe-parser:0.14.14
//> using dep io.circe::circe-literal:0.14.14
//> using dep com.google.genai:google-genai:1.6.0
//> using dep org.slf4j:slf4j-simple:2.0.17

import cats.effect.{IO, IOApp, ExitCode}
import com.google.genai.ResponseStream
import scala.concurrent.{Future, Promise, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.FutureConverters._
import scala.util.{Success, Failure}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOptional
import scala.util.{Try, Failure}
import com.google.genai.AsyncSession
import com.google.genai.Client
import com.google.genai.types.Blob;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Modality;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.VoiceConfig;
import java.util.{Collection, Optional}
import java.util.concurrent.*
import javax.sound.sampled.*
import java.lang.annotation.Target
import scala.sys.*
import java.io.{File, ByteArrayOutputStream, ByteArrayInputStream}

object WavToWavGemini {

  val inputWav = "input.wav"
  val outputWav = "output.wav"
  val chunkSize = 4096
  val modelId = "gemini-2.0-flash-live-001"
  val voiceName = "Aoede"

  def main(args: Array[String]): Unit = {

    // --- 1. Prepare the input file ---
    val audioInputStreamOriginal = AudioSystem.getAudioInputStream(new File(inputWav))
    
    // Convert to 16KHz, 16bit, mono

    val desiredFormat = new AudioFormat(
      16000.0f, // sample rate
      16,       // sample size in bits
      1,        // channels (mono)
      true,     // signed
      false     // little endian
    )

    val audioInputStream = AudioSystem.getAudioInputStream(desiredFormat, audioInputStreamOriginal)
    
    val inputFormat = audioInputStream.getFormat
    println(s"Formato de entrada: $inputFormat")

    // --- 2. Connect to Gemini ---
    val client = new Client()
    val config: LiveConnectConfig = LiveConnectConfig.builder()
      .responseModalities(Modality.Known.AUDIO)
      .speechConfig(
        SpeechConfig.builder()
          .voiceConfig(
            VoiceConfig.builder()
              .prebuiltVoiceConfig(
                PrebuiltVoiceConfig.builder().voiceName(voiceName)
              )
          )
          .languageCode("en-US")
      ).build()

    val session = client.async.live.connect(modelId, config).get()
    println("Connected to Gemini.")

    // --- 3. Prepare the output file ---
    // We will use the same format Gemini uses to respond (24kHz, 16bit, mono, signed, little-endian)
    val outputFormat = new AudioFormat(24000.0f, 16, 1, true, false)
    val rawOut = new ByteArrayOutputStream()

    // --- 4. Read input file and send as chunks ---
    val buffer = new Array[Byte](chunkSize)
    var bytesRead = 0
    while ({ bytesRead = audioInputStream.read(buffer); bytesRead != -1 }) {
      val chunk = if (bytesRead == chunkSize) buffer else buffer.take(bytesRead)
      val inputParams = LiveSendRealtimeInputParameters.builder()
        .media(Blob.builder().mimeType("audio/pcm").data(chunk))
        .build()
      session.sendRealtimeInput(inputParams)
    }
    audioInputStream.close()
    println("Audio sent to Gemini.")

    // --- 5. Start listening for responses ---
    val receiveDone = session.receive { message =>
      message.serverContent().ifPresent { content =>
        // We receive audio chunks from the model
        content.modelTurn().toScala
          .flatMap(_.parts().toScala)
          .toList
          .map(_.asScala)
          .flatten
          .flatMap(_.inlineData().toScala.flatMap(_.data().toScala))
          .foreach { audioBytes =>
            rawOut.write(audioBytes)
          }

        if (content.turnComplete().orElse(false)) {
            println("Model finished speaking (turnComplete).")
            session.close()
            val audioBytes = rawOut.toByteArray
            val bais = new ByteArrayInputStream(audioBytes)
            val outAudioStream = new AudioInputStream(bais, outputFormat, audioBytes.length / outputFormat.getFrameSize)
            AudioSystem.write(outAudioStream, AudioFileFormat.Type.WAVE, new File(outputWav))
            outAudioStream.close()
            println(s"Response saved to $outputWav")
            println("Response received and session closed.")
        }
      }
    }
    
  }
}