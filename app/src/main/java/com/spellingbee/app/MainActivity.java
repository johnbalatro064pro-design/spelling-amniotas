package com.spellingbee.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class MainActivity extends AppCompatActivity implements RecognitionListener {
    private static final int MIC_REQ = 42;
    private static final String MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";

    private EditText wordInput;
    private TextView status, wordCounter, wordPrompt, targetWord, spelledLetters, heard, feedback, review;
    private Button voiceButton;
    private Model model;
    private SpeechService speechService;

    private final ArrayList<String> words = new ArrayList<>();
    private final ArrayList<String> missed = new ArrayList<>();
    private int wordIndex = -1;
    private int letterIndex = 0;
    private StringBuilder typed = new StringBuilder();
    private boolean showingWord = false;

    private SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("bee", MODE_PRIVATE);
        bind();
        buildKeyboard();
        loadSavedWords();
        requestMic();
        loadModelAsync();
    }

    private void bind() {
        wordInput=findViewById(R.id.wordInput); status=findViewById(R.id.status);
        wordCounter=findViewById(R.id.wordCounter); wordPrompt=findViewById(R.id.wordPrompt);
        targetWord=findViewById(R.id.targetWord); spelledLetters=findViewById(R.id.spelledLetters);
        voiceButton=findViewById(R.id.voiceButton); heard=findViewById(R.id.heard);
        feedback=findViewById(R.id.feedback); review=findViewById(R.id.review);

        findViewById(R.id.saveWords).setOnClickListener(v -> saveWords());
        findViewById(R.id.startRound).setOnClickListener(v -> startRound());
        findViewById(R.id.showWord).setOnClickListener(v -> {
            showingWord=!showingWord;
            targetWord.setVisibility(showingWord?View.VISIBLE:View.GONE);
        });
        findViewById(R.id.backspace).setOnClickListener(v -> {
            if(typed.length()>0){ typed.deleteCharAt(typed.length()-1); updateTyped(); }
        });
        findViewById(R.id.clearLetters).setOnClickListener(v -> {typed.setLength(0); updateTyped();});
        findViewById(R.id.checkSpelling).setOnClickListener(v -> checkSpelling());
        voiceButton.setOnClickListener(v -> speakNextLetter());
    }

    private void buildKeyboard() {
        GridLayout grid=findViewById(R.id.keyboard);
        for(char ch='A';ch<='Z';ch++){
            Button b=new Button(this); b.setText(String.valueOf(ch)); b.setTextSize(16);
            b.setOnClickListener(v -> { typed.append(((Button)v).getText()); updateTyped(); });
            GridLayout.LayoutParams p=new GridLayout.LayoutParams();
            p.width=0; p.height=WrapContent(); p.columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);
            grid.addView(b,p);
        }
    }
    private int WrapContent(){ return GridLayout.LayoutParams.WRAP_CONTENT; }

    private void loadSavedWords(){
        String raw=prefs.getString("words","");
        if(!raw.isEmpty()){ wordInput.setText(raw.replace("|","\n")); parseWords(raw.replace("|","\n")); }
    }
    private void saveWords(){
        parseWords(wordInput.getText().toString());
        prefs.edit().putString("words", String.join("|",words)).apply();
        status.setText(words.size()+" words saved.");
    }
    private void parseWords(String text){
        words.clear();
        for(String s:text.split("\\r?\\n")){
            s=s.trim();
            if(!s.isEmpty()) words.add(s.replaceAll("[^A-Za-z'-]",""));
        }
    }
    private void startRound(){
        if(words.isEmpty()) parseWords(wordInput.getText().toString());
        if(words.isEmpty()){status.setText("Enter at least one word.");return;}
        Collections.shuffle(words);
        wordIndex=0; showCurrentWord(false);
    }
    private void showCurrentWord(boolean completed){
        if(wordIndex<0||wordIndex>=words.size()) return;
        letterIndex=0; typed.setLength(0); showingWord=false;
        String w=words.get(wordIndex).toUpperCase(Locale.US);
        wordCounter.setText("Word "+(wordIndex+1)+" of "+words.size());
        wordPrompt.setText("Spell the word");
        targetWord.setText(w); targetWord.setVisibility(View.GONE);
        spelledLetters.setText("Your letters: ");
        heard.setText("Heard: —"); feedback.setText("Speak or tap letters.");
        voiceButton.setEnabled(model!=null);
        status.setText("Ready.");
    }
    private String currentWord(){ return wordIndex>=0&&wordIndex<words.size()?words.get(wordIndex).toUpperCase(Locale.US):""; }
    private void updateTyped(){ spelledLetters.setText("Your letters: "+typed.toString()); }

    private void checkSpelling(){
        String expected=currentWord();
        if(expected.isEmpty()) return;
        String got=typed.toString().toUpperCase(Locale.US);
        if(got.equals(expected)){
            feedback.setText("✅ Correct!");
            if(wordIndex+1<words.size()){ wordIndex++; new Handler(Looper.getMainLooper()).postDelayed(()->showCurrentWord(false),700); }
            else { status.setText("Round complete!"); addReviewSummary(); }
        } else {
            feedback.setText("❌ Not quite. Try again.");
            if(!missed.contains(expected)) missed.add(expected);
            review.setText("Missed: "+String.join(", ",missed));
        }
    }

    private void speakNextLetter(){
        if(model==null){status.setText("Speech model is still loading.");return;}
        if(currentWord().isEmpty() || letterIndex>=currentWord().length()){
            status.setText("Finish or start a word first."); return;
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestMic(); return;
        }
        stopSpeech();
        try {
            String target=String.valueOf(currentWord().charAt(letterIndex)).toLowerCase(Locale.US);
            String grammar="["a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z","[unk]"]";
            Recognizer r=new Recognizer(model,16000.0f,grammar);
            speechService=new SpeechService(r,16000.0f);
            heard.setText("Heard: listening…");
            feedback.setText("🎤 Say the letter "+target.toUpperCase(Locale.US));
            speechService.startListening(this);
        } catch(Exception e){ status.setText("Microphone error: "+e.getMessage()); }
    }

    private void stopSpeech(){
        if(speechService!=null){ speechService.stop(); speechService.shutdown(); speechService=null; }
    }

    @Override public void onPartialResult(String hypothesis){
        runOnUiThread(()->heard.setText("Heard: "+extractText(hypothesis)));
    }
    @Override public void onResult(String hypothesis){
        String text=extractText(hypothesis);
        runOnUiThread(()->handleVoice(text));
    }
    @Override public void onFinalResult(String hypothesis){ }
    @Override public void onTimeout(){ runOnUiThread(()->{status.setText("I didn't hear a letter. Try again."); stopSpeech();}); }
    @Override public void onError(Exception e){ runOnUiThread(()->{status.setText("Voice error: "+e.getMessage()); stopSpeech();}); }

    private String extractText(String json){
        try { return new JSONObject(json).optString("text","").trim().toUpperCase(Locale.US); }
        catch(Exception e){ return json==null?"":json; }
    }
    private void handleVoice(String text){
        stopSpeech();
        heard.setText("Heard: "+(text.isEmpty()?"—":text));
        String expected=String.valueOf(currentWord().charAt(letterIndex)).toUpperCase(Locale.US);
        boolean ok=text.equals(expected) || text.startsWith(expected+" ");
        if(ok){
            typed.append(expected); updateTyped();
            feedback.setText("✅ Correct — "+expected);
            letterIndex++;
            if(letterIndex>=currentWord().length()) checkSpelling();
            else status.setText("Next letter.");
        }else{
            feedback.setText("❌ Expected "+expected+". Try again.");
            if(!missed.contains(currentWord())) missed.add(currentWord());
            review.setText("Missed: "+String.join(", ",missed));
        }
    }

    private void addReviewSummary(){
        if(missed.isEmpty()) review.setText("🎉 No missed words this round!");
        else review.setText("Missed words: "+String.join(", ",missed));
    }

    private void requestMic(){
        if(android.os.Build.VERSION.SDK_INT>=23 &&
           checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQ);
    }

    private void loadModelAsync(){
        status.setText("Preparing free offline speech model…");
        StorageService.unpack(this, "model-en-us", "model",
            (modelDir)->{
                model=new Model(modelDir.getAbsolutePath());
                runOnUiThread(()->{status.setText("✅ Voice checker ready."); voiceButton.setEnabled(true);});
            },
            exception->{
                // First-run download because the APK remains reasonably small.
                downloadAndUnpackModel();
            });
    }

    private void downloadAndUnpackModel(){
        new Thread(()->{
            File zip=new File(getFilesDir(),"vosk-model.zip");
            try{
                URL u=new URL(MODEL_URL);
                HttpURLConnection c=(HttpURLConnection)u.openConnection();
                c.setConnectTimeout(20000); c.setReadTimeout(60000);
                c.connect();
                if(c.getResponseCode()!=200) throw new IOException("Model download HTTP "+c.getResponseCode());
                try(InputStream in=new BufferedInputStream(c.getInputStream());
                    FileOutputStream out=new FileOutputStream(zip)){
                    byte[] buf=new byte[8192]; int n; long total=0;
                    while((n=in.read(buf))!=-1){out.write(buf,0,n);total+=n;
                        long mb=total/1024/1024;
                        runOnUiThread(()->status.setText("Downloading free speech model… "+mb+" MB"));
                    }
                }
                unzip(zip,new File(getFilesDir(),"model"));
                zip.delete();
                File modelDir=new File(getFilesDir(),"model");
                File[] kids=modelDir.listFiles();
                if(kids!=null && kids.length==1 && kids[0].isDirectory()){
                    modelDir=kids[0];
                }
                model=new Model(modelDir.getAbsolutePath());
                runOnUiThread(()->{status.setText("✅ Voice checker ready. Model saved for offline use.");voiceButton.setEnabled(true);});
            }catch(Exception e){
                runOnUiThread(()->status.setText("Could not download speech model. Tap again after internet is available."));
            }
        }).start();
    }

    private void unzip(File zip,File dest)throws IOException{
        dest.mkdirs();
        try(java.util.zip.ZipInputStream zis=new java.util.zip.ZipInputStream(new FileInputStream(zip))){
            java.util.zip.ZipEntry e;
            byte[] buf=new byte[8192];
            while((e=zis.getNextEntry())!=null){
                File f=new File(dest,e.getName());
                if(!f.getCanonicalPath().startsWith(dest.getCanonicalPath()+File.separator))
                    throw new IOException("Unsafe zip");
                if(e.isDirectory()) f.mkdirs(); else {
                    File p=f.getParentFile(); if(p!=null)p.mkdirs();
                    try(FileOutputStream out=new FileOutputStream(f)){int n;while((n=zis.read(buf))!=-1)out.write(buf,0,n);}
                }
            }
        }
    }

    @Override protected void onDestroy(){stopSpeech();if(model!=null)model.close();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int r,@NonNull String[] p,@NonNull int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==MIC_REQ && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED) status.setText("Microphone permission granted.");
    }
}
