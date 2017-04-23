package org.sil.storyproducer.controller.draft;

import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.sil.storyproducer.R;
import org.sil.storyproducer.model.SlideText;
import org.sil.storyproducer.model.StoryState;
import org.sil.storyproducer.tools.BitmapScaler;
import org.sil.storyproducer.tools.StorySharedPreferences;
import org.sil.storyproducer.tools.file.AudioFiles;
import org.sil.storyproducer.tools.file.ImageFiles;
import org.sil.storyproducer.tools.file.TextFiles;
import org.sil.storyproducer.tools.media.AudioPlayer;
import org.sil.storyproducer.tools.media.wavaudio.WavAudioRecorder;
import org.sil.storyproducer.tools.media.wavaudio.WavFileConcatenator;
import org.sil.storyproducer.tools.toolbar.RecordingToolbar;
import org.sil.storyproducer.tools.toolbar.RecordingToolbar.RecordingListener;


import java.io.File;
import java.io.FileNotFoundException;

/**
 * The fragment for the Draft view. This is where a user can draft out the story slide by slide
 */
public class DraftFrag extends Fragment {
    private View rootView;
    public static final String SLIDE_NUM = "CURRENT_SLIDE_NUM_OF_FRAG";
    private int slideNumber;
    private SlideText slideText;
    private AudioPlayer narrationAudioPlayer;
    private String narrationFilePath;
    private String recordFilePath;
    private ImageButton narrationPlayButton;
    private TextView slideNumberText;
    private RecordingToolbar recordingToolbar;

    private WavAudioRecorder wavAudioRecorder;

    public DraftFrag() {
        super();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle passedArgs = this.getArguments();

        slideNumber = passedArgs.getInt(SLIDE_NUM);
        slideText = TextFiles.getSlideText(StoryState.getStoryName(), slideNumber);
        setRecordFilePath();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        // The last two arguments ensure LayoutParams are inflated
        // properly.
        rootView = inflater.inflate(R.layout.fragment_draft, container, false);
        View rootViewToolbar = inflater.inflate(R.layout.toolbar_for_recording, container, false);

        setUiColors();
        setPic(rootView.findViewById(R.id.fragment_draft_image_view), slideNumber);
        setScriptureText(rootView.findViewById(R.id.fragment_draft_scripture_text));
        setReferenceText(rootView.findViewById(R.id.fragment_draft_reference_text));
        setNarrationButton(rootView.findViewById(R.id.fragment_draft_narration_button));
        setToolbar(rootViewToolbar);
        slideNumberText = (TextView) rootView.findViewById(R.id.slide_number_text);
        slideNumberText.setText(slideNumber + 1 + "");


        setTestButtons(rootView.findViewById(R.id.fragment_draft_test_button1), rootView.findViewById(R.id.fragment_draft_test_button2));

        File fil = AudioFiles.getDraftWav(StoryState.getStoryName(), slideNumber);
        if (!fil.exists()) {
            wavAudioRecorder = new WavAudioRecorder(getActivity(), fil, slideNumber);
        } else {
            wavAudioRecorder = new WavAudioRecorder(getActivity(), AudioFiles.getDraftTempWav(StoryState.getStoryName(), slideNumber), slideNumber);
        }

        return rootView;
    }

    public void setTestButtons(View... view) {
        Button testButton;
        if (view[0] instanceof Button) {
            testButton = (Button) view[0];

            testButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    File fil = AudioFiles.getDraftWav(StoryState.getStoryName(), slideNumber);
                    if (fil.exists()) {
                        wavAudioRecorder.recordToPath(AudioFiles.getDraftTempWav(StoryState.getStoryName(), slideNumber));
                    }
                    if (!wavAudioRecorder.isRecording()) {
                        wavAudioRecorder.startRecording();
                        Toast.makeText(getContext(), "started to record.", Toast.LENGTH_SHORT).show();

                    } else {
                        wavAudioRecorder.stopRecording();
                        Toast.makeText(getContext(), "stopped recording.", Toast.LENGTH_SHORT).show();
                        if (AudioFiles.getDraftTempWav(StoryState.getStoryName(), slideNumber).exists()) {
                            try {
                                WavFileConcatenator.ConcatenateAudioFiles(AudioFiles.getDraftWav(StoryState.getStoryName(), slideNumber), AudioFiles.getDraftTempWav(StoryState.getStoryName(), slideNumber));
                            } catch (FileNotFoundException e) {

                            }

                        }
                    }
                }
            });
        }

        if (view[1] instanceof Button) {
            testButton = (Button) view[1];

            testButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    File file = AudioFiles.getDraftWav(StoryState.getStoryName(), slideNumber);

                    if (file.exists()) {
                        final AudioPlayer audioPlayer = new AudioPlayer();
                        audioPlayer.playWithPath(file.getPath());
                        audioPlayer.audioCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mp) {
                                audioPlayer.releaseAudio();
                            }
                        });
//                        try{
//                            FileInputStream fil = new FileInputStream(file);
//                            byte [] bytes = new byte[(int)file.length()];
//                            fil.read(bytes);
//                            AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT , (int)file.length(), AudioTrack.MODE_STREAM);
//                            audioTrack.play();
//                            audioTrack.write(bytes, 0, bytes.length);
//                        }catch(FileNotFoundException e){
//
//                        }catch(IOException e){
//
//                        }catch(Exception e){
//
//                        }


                    } else {
                        Toast.makeText(getContext(), "Recording does not exist!", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }


    }

    /**
     * This function serves to handle draft page changes and stops the audio streams from
     * continuing.
     *
     * @param isVisibleToUser
     */
    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        // Make sure that we are currently visible
        if (this.isVisible()) {
            // If we are becoming invisible, then...
            if (!isVisibleToUser) {
                if (recordingToolbar != null) {
                    recordingToolbar.closeToolbar();
                }
            }
        }
    }

    /**
     * This function serves to stop the audio streams from continuing after the draft has been
     * put on pause.
     */
    @Override
    public void onPause() {
        super.onPause();
        if (recordingToolbar != null) {
            recordingToolbar.closeToolbar();
        }
    }

    /**
     * This function serves to stop the audio streams from continuing after the draft has been
     * put on stop.
     */
    @Override
    public void onStop() {
        super.onStop();
        if (recordingToolbar != null) {
            recordingToolbar.closeToolbar();
        }
    }

    /**
     * sets the playback path
     */
    public void setPlayBackPath() {
        String playBackFilePath = AudioFiles.getDraft(StoryState.getStoryName(), slideNumber).getPath();
        recordingToolbar.setPlaybackRecordFilePath(playBackFilePath);
    }

    /**
     * This function sets the first slide of each story to the blue color in order to prevent
     * clashing of the grey starting picture.
     */
    private void setUiColors() {
        if (slideNumber == 0) {
            RelativeLayout rl = (RelativeLayout) rootView.findViewById(R.id.fragment_draft_root_relayout_layout);
            rl.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.primaryDark));
            rl = (RelativeLayout) rootView.findViewById(R.id.fragment_draft_envelope);
            rl.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.primaryDark));
            rl = (RelativeLayout) rootView.findViewById(R.id.fragment_draft_text_envelope);
            rl.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.primaryDark));


            TextView tv = (TextView) rootView.findViewById(R.id.fragment_draft_scripture_text);
            tv.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.primaryDark));
            tv = (TextView) rootView.findViewById(R.id.fragment_draft_reference_text);
            tv.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.primaryDark));
        }
    }

    /**
     * This function allows the picture to scale with the phone's screen size.
     *
     * @param aView    The ImageView that will contain the picture.
     * @param slideNum The slide number to grab the picture from the files.
     */
    private void setPic(View aView, int slideNum) {
        if (aView == null || !(aView instanceof ImageView)) {
            return;
        }

        ImageView slideImage = (ImageView) aView;
        Bitmap slidePicture = ImageFiles.getBitmap(StoryState.getStoryName(), slideNum);

        if (slidePicture == null) {
            Snackbar.make(rootView, R.string.dramatization_draft_no_picture, Snackbar.LENGTH_SHORT).show();
        }

        //Get the height of the phone.
        DisplayMetrics phoneProperties = getContext().getResources().getDisplayMetrics();
        int height = phoneProperties.heightPixels;
        double scalingFactor = 0.4;
        height = (int) (height * scalingFactor);

        //scale bitmap
        slidePicture = BitmapScaler.scaleToFitHeight(slidePicture, height);

        //Set the height of the image view
        slideImage.getLayoutParams().height = height;
        slideImage.requestLayout();

        slideImage.setImageBitmap(slidePicture);
    }

    /**
     * Sets the main text of the layout.
     *
     * @param aView The text view that will be filled with the verse's text.
     */
    private void setScriptureText(View aView) {
        if (aView == null || !(aView instanceof TextView)) {
            return;
        }
        TextView textView = (TextView) aView;
        textView.setText(slideText.getContent());
    }

    /**
     * This function sets the reference text.
     *
     * @param aView The view that will be populated with the reference text.
     */
    private void setReferenceText(View aView) {
        if (aView == null || !(aView instanceof TextView)) {
            return;
        }
        TextView textView = (TextView) aView;

        String[] titleNamePriority = new String[]{slideText.getReference(),
                slideText.getSubtitle(), slideText.getTitle()};

        for (String title : titleNamePriority) {
            if (title != null && !title.equals("")) {
                textView.setText(title);
                return;
            }
        }

        textView.setText(R.string.draft_bible_story);
    }

    /**
     * This function sets the narration playback to the correct audio file. Also, the narration
     * button will have a listener added to it in order to detect playback when pressed.
     *
     * @param aView
     */
    private void setNarrationButton(View aView) {
        if (aView == null || !(aView instanceof ImageButton)) {
            return;
        }
        narrationFilePath = AudioFiles.getLWC(StoryState.getStoryName(), slideNumber).getPath();
        narrationPlayButton = (ImageButton) aView;
        narrationPlayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (narrationFilePath == null) {
                    Snackbar.make(rootView, R.string.draft_playback_no_narration_audio, Snackbar.LENGTH_SHORT).show();
                } else {
                    if (narrationAudioPlayer != null && narrationAudioPlayer.isAudioPlaying()) {
                        narrationAudioPlayer.stopAudio();
                        narrationAudioPlayer.releaseAudio();
                        narrationPlayButton.setBackgroundResource(R.drawable.ic_menu_play);
                    } else {
                        //stop other playback streams.
                        recordingToolbar.stopToolbarMedia();
                        narrationAudioPlayer = new AudioPlayer();
                        narrationAudioPlayer.audioCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mp) {
                                narrationAudioPlayer.releaseAudio();
                                narrationPlayButton.setBackgroundResource(R.drawable.ic_menu_play);
                            }
                        });
                        narrationAudioPlayer.playWithPath(narrationFilePath);
                        if (recordingToolbar != null) {
                            recordingToolbar.onToolbarTouchStopAudio(narrationPlayButton, R.drawable.ic_menu_play, narrationAudioPlayer);
                        }
                        narrationPlayButton.setBackgroundResource(R.drawable.ic_stop_white_36dp);
                        Toast.makeText(getContext(), R.string.draft_playback_narration_audio, Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void setRecordFilePath() {
        int nextDraftIndex = AudioFiles.getDraftTitles(StoryState.getStoryName(), slideNumber).length + 1;
        File recordFile = AudioFiles.getDraft(StoryState.getStoryName(), slideNumber,
                "Draft " + nextDraftIndex);
        while (recordFile.exists()) {
            nextDraftIndex++;
            recordFile = AudioFiles.getDraft(StoryState.getStoryName(), slideNumber,
                    "Draft " + nextDraftIndex);
        }
        recordFilePath = recordFile.getPath();
    }

    /**
     * Initializes the toolbar and toolbar buttons.
     */
    private void setToolbar(View toolbar) {
        if (rootView instanceof RelativeLayout) {
            String playBackFilePath = AudioFiles.getDraft(StoryState.getStoryName(), slideNumber).getPath();
            RecordingListener recordingListener = new RecordingListener() {
                @Override
                public void stoppedRecording() {
                    String[] splitPath = recordFilePath.split("translation" + "\\d+" + "_");    //get just the title from the path
                    String title = splitPath[1].replace(".mp3", "");
                    StorySharedPreferences.setDraftForSlideAndStory(title, slideNumber, StoryState.getStoryName());
                    setRecordFilePath();
                    recordingToolbar.setRecordFilePath(recordFilePath);
                    setPlayBackPath();
                }

                @Override
                public void startedRecordingOrPlayback() {
                    //not used here
                }
            };
            DraftListRecordingsModal modal = new DraftListRecordingsModal(getContext(), slideNumber, this);

            recordingToolbar = new RecordingToolbar(getActivity(), toolbar, (RelativeLayout) rootView, true, false, true, playBackFilePath, recordFilePath, modal, recordingListener);
            recordingToolbar.keepToolbarVisible();
            recordingToolbar.stopToolbarMedia();
        }
    }

    //used in the DraftListRecordingsModal
    //TODO add to the area where the other public functions in this class.
    public void stopPlayBackAndRecording() {
        recordingToolbar.stopToolbarMedia();
    }

    /** Don't remove! below code  **/
//    /**
//     * This function adds two different audio files together to make one audio file into an
//     * .mp3 file. More comments will be added to this function later.
//     */
//    private void ConcatenateAudioFiles() {
//        Movie finalFile = new Movie();
//        String writtenToAudioFile = String.format(recordFilePath.substring(0, recordFilePath.indexOf(".m4a")) + "final.m4a");
//        Movie movieArray[];
//
//        try {
//            if (!new File(recordFilePath).exists()) {
//                movieArray = new Movie[]{MovieCreator.build(tempRecordFilePath)};
//            } else {
//                movieArray = new Movie[]{MovieCreator.build(recordFilePath),
//                        MovieCreator.build(tempRecordFilePath)};
//            }
//
//            List<Track> audioTrack = new ArrayList<>();
//
//            for (int i = 0; i < movieArray.length; i++)
//                for (Track t : movieArray[i].getTracks()) {
//                    if (t.getHandler().equals("soun")) {
//                        audioTrack.add(t);
//                    }
//                }
//
//            if (!audioTrack.isEmpty()) {
//                finalFile.addTrack(new AppendTrack(audioTrack.toArray(new Track[audioTrack.size()])));
//            }
//
//            Container out = new DefaultMp4Builder().build(finalFile);
//
//            FileChannel fc = new RandomAccessFile(writtenToAudioFile, "rwd").getChannel();
//            out.writeContainer(fc);
//            fc.close();
//
//            tryDeleteFile(recordFilePath);
//            boolean renamed = (new File(writtenToAudioFile).renameTo(tryCreateFile(recordFilePath)));
//            if (renamed) {
//                //delete old file
//                tryDeleteFile(writtenToAudioFile);
//            }
//
//        } catch (IOException e) {
//            Log.e(getActivity().toString(), e.getMessage());
//        }
//    }
//
//    /**
//     * Tries to create a new file.
//     *
//     * @param filePath The file path where a file should be created at.
//     * @return The file instantiation of the file that was created at the filePath.
//     */
//    private File tryCreateFile(String filePath) {
//        File toReturnFile = new File(filePath);
//        if (!toReturnFile.exists()) {
//            try {
//                toReturnFile.setExecutable(true);
//                toReturnFile.setReadable(true);
//                toReturnFile.setWritable(true);
//                toReturnFile.createNewFile();
//            } catch (IOException e) {
//                Log.w(getActivity().toString(), "Could not create file for recording!");
//            }
//        }
//
//        return toReturnFile;
//    }


    /** Don't remove above code!! **/

}
