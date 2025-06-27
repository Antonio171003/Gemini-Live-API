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
import com.google.genai.types.*
import java.util.{Collection, Optional}
import java.util.concurrent.*
import javax.sound.sampled.*
import java.lang.annotation.Target
import scala.sys.*

object LiveAudioConversationAsync {

  // --- Audio Configuration ---
  val micAudioFormat: AudioFormat =
      new AudioFormat(16000.0f, 16, 1, true, false); // 16kHz, 16-bit, mono, signed, little-endian
  val speakerAudioFormat: AudioFormat =
      new AudioFormat(24000.0f, 16, 1, true, false); // 24kHz, 16-bit, mono, signed, little-endian

  // How many bytes to read from mic/send to API at a time
  val chunkSize: Int = 4096;

  var running: Boolean = true
  var speakerPlaying: Boolean = false
  var microphoneLine: TargetDataLine = getMicrophoneLine
  var speakerLine: SourceDataLine = getSpeakerLine
  var session: AsyncSession = null
  val micExecutor: ExecutorService = Executors.newSingleThreadExecutor() 

  /** Creates the parameters for sending an audio chunk. */
  def createAudioContent(audioData: Array[Byte]): LiveSendRealtimeInputParameters = {
    if (audioData == null) {
      println("Error: Audio is null");
      null
    }
    else {
      LiveSendRealtimeInputParameters.builder()
        .media(Blob.builder().mimeType("audio/pcm").data(audioData))
        .build();
    }
  }
  
  /** Reads audio from the microphone and sends it to the API session. Runs in a separate thread. */
  def sendMicrophoneAudio: Unit = {
    val buffer: Array[Byte] = new Array[Byte](chunkSize)
    var bytesRead: Int = 0

    while (running && microphoneLine != null && microphoneLine.isOpen()) do {
      bytesRead = microphoneLine.read(buffer, 0, buffer.length);

      if(bytesRead > 0 && !speakerPlaying) {
        var audioChunk = new Array[Byte](bytesRead)
        System.arraycopy(buffer, 0, audioChunk, 0, bytesRead)

        // Send the audio chunk asynchronously
        if (session != null) {
          session
              .sendRealtimeInput(createAudioContent(audioChunk))
              .exceptionally(
                  e => {
                    System.err.println("Error sending audio chunk: " + e.getMessage())
                    null
                  })
        }
        else {
          println("Microphone reading stopped.")
        }
      } else if (bytesRead == -1) {
        println("Microphone stream ended unexpectedly.")
        running = false; // Stop the loop if stream ends
        println("Microphone reading stopped.")
      }
    }

  }

  /** Gets and opens the microphone line. */
  def getMicrophoneLine: TargetDataLine = {
    val micInfo: DataLine.Info = DataLine.Info(classOf[TargetDataLine], micAudioFormat)
    if !AudioSystem.isLineSupported(micInfo) then
      throw LineUnavailableException(s"Microphone line not supported for format: $micAudioFormat")
    val line: TargetDataLine = AudioSystem.getLine(micInfo).asInstanceOf[TargetDataLine]
    line.open(micAudioFormat)
    println("Microphone line opened.")
    line
  }

  /** Gets and opens the speaker line. */
  def getSpeakerLine: SourceDataLine = {
    val speakerInfo: DataLine.Info = DataLine.Info(classOf[SourceDataLine], speakerAudioFormat)
    if (!AudioSystem.isLineSupported(speakerInfo)) {
      throw new LineUnavailableException(
          s"Speaker line not supported for format: $speakerAudioFormat")
    }
    val line: SourceDataLine = AudioSystem.getLine(speakerInfo).asInstanceOf[SourceDataLine]
    line.open(speakerAudioFormat)
    println("Speaker line opened.")
    line
  }

  /** Closes an audio line safely. */
  def closeAudioLine(line: Line): Unit = {
    if (line != null && line.isOpen()) {
      line.close();
    }
    else ()
  }

  /** Callback function to handle incoming audio messages from the server. */
  def handleAudioResponse(message: LiveServerMessage): Unit = {
    val jFalse = java.lang.Boolean.FALSE
    val jTrue = java.lang.Boolean.TRUE
    message.serverContent()
    .ifPresent{content =>
        if (content.turnComplete().orElse(false)) {
          // When interrupted, Gemini sends a turn_complete.
          // Stop the speaker if the turn is complete.
          if (speakerLine != null && speakerLine.isOpen()) {
            speakerLine.flush()
          }
          else ()
        }
        else {
          content.modelTurn().toScala
            .flatMap(modelTurn => modelTurn.parts().toScala)
            .toList
            .map(_.asScala)
            .flatten
            .flatMap(part => part.inlineData().toScala.flatMap(_.data().toScala))
            .foreach { audioBytes =>
              if speakerLine != null && speakerLine.isOpen then
                speakerLine.write(audioBytes, 0, audioBytes.length)
            }
        }
    }
  }

  @main def main: Unit = {

    val client: Client = new Client();
    val modelId: String = "gemini-2.0-flash-live-001"
    val voiceName = "Aoede"

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

    scala.sys.addShutdownHook {
      
      println("\nShutting down...")
      running = false
      micExecutor.shutdown()

      Try {
        if (!micExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          println("Mic executor did not terminate gracefully.")
          micExecutor.shutdownNow()
        }
        else ()
      } match {
        case Failure(exception) =>
          micExecutor.shutdownNow()
          Thread.currentThread().interrupt()
          println(s"Error shutting down mic executor: ${exception.getMessage}")
        case _ => ()
      }

      if session != null then
        Try(session.close().get(5, TimeUnit.SECONDS)) match {
          case Failure(e) =>
            System.err.println(s"Error closing API session: ${e.getMessage}")
          case _ =>
            println("API session closed.")
        }
      else ()

      closeAudioLine(microphoneLine)
      closeAudioLine(speakerLine)
      println("Audio lines closed.")

    }

    Try {
      println("Connecting to Gemini Live API...")
      session = client.async.live.connect(modelId, config).get()
      println("Connected.")

      // --- Start Audio Lines ---
      microphoneLine.start()
      speakerLine.start()
      println("Microphone and speakers started. Speak now (Press Ctrl+C to exit)...")

      // --- Start Receiving Audio Responses ---
      val receiveFuture = session.receive(LiveAudioConversationAsync.handleAudioResponse)
      println("Receive stream started.")

      // --- Start Sending Microphone Audio ---
      val sendFuture = CompletableFuture.runAsync(() => LiveAudioConversationAsync.sendMicrophoneAudio, micExecutor)

      CompletableFuture.anyOf(receiveFuture, sendFuture)
        .handle[Void]((res, err) => {
          if err != null then
            System.err.println(s"An error occurred in sending/receiving: ${err.getMessage}")
            System.exit(1)
          null
        })
        .get()
    } match {
      case Failure(e) =>
        println(s"Unexpected error: ${e.getMessage}")
        e.printStackTrace()
        System.exit(1)
      case _ => 
    }

  }

}
