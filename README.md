# Gemini-Live-API

Use the Gemini AI Live API from Scala 3 with [Java-genai](https://github.com/googleapis/java-genai).

## Requirements

- Scala 3.3+
- Java 17+
- Scala CLI
- A Google Gemini API Key

## Setup

First, export your Gemini API key as an environment variable:

```sh
export GOOGLE_API_KEY="YOUR_API_KEY_HERE"
```

## Usage

### WavToWavGemini

Sends a `.wav` audio file to the Gemini model, waits for the response, saves the model's answer in a `.wav` file, and closes the session.

**How to use:**

1. Place your input audio as `input.wav` in the same directory.
2. Run the program with Scala CLI:

    ```sh
    scala-cli WavToWavGemini.scala
    ```

3. The response will be saved as `output.wav` when finished.

### LiveAudioConversationAsync

A live audio chat with Gemini. Captures audio from your microphone, sends it to the model, and plays back the model's audio response through your speakers.

**How to use:**

1. Run the program with Scala CLI:

    ```sh
    scala-cli LiveAudioConversationAsync.scala
    ```

## References

- [Google Gemini Java API](https://github.com/googleapis/java-genai)
- [Official Gemini Documentation](https://ai.google.dev/docs)
