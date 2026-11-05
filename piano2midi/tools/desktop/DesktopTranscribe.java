import com.hft.piano2midi.core.*;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Desktop check: transcribe a 16-bit PCM WAV with the same Java core as the app.
 *  javac -cp onnxruntime.jar -d out ../../android/app/src/main/java/com/hft/piano2midi/core/*.java DesktopTranscribe.java
 *  java -cp out:onnxruntime.jar DesktopTranscribe in.wav model.onnx out.mid */
public class DesktopTranscribe {
    public static void main(String[] a) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(new File(a[0]));
        AudioFormat f = ais.getFormat();
        byte[] raw = ais.readAllBytes();
        int ch = f.getChannels(), sr = (int) f.getSampleRate();
        int frames = raw.length / (2 * ch);
        ByteBuffer bb = ByteBuffer.wrap(raw).order(f.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        float[] mono = new float[frames];
        for (int i = 0; i < frames; i++) { float acc = 0; for (int c = 0; c < ch; c++) acc += bb.getShort() / 32768f; mono[i] = acc / ch; }
        long t0 = System.currentTimeMillis();
        float[] wav = Resampler.resample(mono, sr, 16000, null);
        long t1 = System.currentTimeMillis();
        float[][] feat = new FeatureExtractor().logMel(wav, null);
        long t2 = System.currentTimeMillis();
        System.out.println("resample ms " + (t1 - t0) + ", features ms " + (t2 - t1) + ", frames " + feat.length + ", samples16k " + wav.length);
        if (System.getenv("DUMP_FEATURES") != null) try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(System.getenv("DUMP_FEATURES"))))) {
            ByteBuffer o = ByteBuffer.allocate(feat.length * 256 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (float[] r : feat) for (float v : r) o.putFloat(v);
            os.write(o.array());
        }
        if (a.length > 1 && a[1].equals("--features-only")) return;
        OnnxModel m = new OnnxModel(a[1], 4);
        long t3 = System.currentTimeMillis();
        Transcriber.Result r = Transcriber.transcribe(feat, m, null);
        long t4 = System.currentTimeMillis();
        List<Note> notes = NoteDecoder.decode(r, 0.5, 0.5, 0.5, NoteDecoder.OffsetMode.SHORTER, true);
        System.out.println("model ms " + (t4 - t3) + ", notes " + notes.size());
        try (PrintWriter pw = new PrintWriter((a.length > 2 ? a[2] : "out.mid") + ".txt")) { for (Note n : notes) pw.println(n); }
        try (FileOutputStream os = new FileOutputStream(a.length > 2 ? a[2] : "out.mid")) { MidiWriter.write(notes, "test", os); }
    }
}
