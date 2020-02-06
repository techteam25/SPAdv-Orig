// TODO @pwhite: WholeStoryBackTranslationActivity and LearnActivity are
// extremely similar. The latter allows viewing of a *template*, and the former
// allows viewing of a *story*, which is essentially a translated template. The
// major difference is that the backtranslation should also allow uploading,
// but this is does not prevent us from extracting the common functionality.
package org.sil.storyproducer.controller.remote

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.support.graphics.drawable.VectorDrawableCompat
import android.support.v4.app.Fragment
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.android.volley.VolleyError
import org.apache.commons.io.IOUtils
import org.json.JSONObject
import org.sil.storyproducer.BuildConfig
import org.sil.storyproducer.R
import org.sil.storyproducer.controller.phase.PhaseBaseActivity
import org.sil.storyproducer.model.SLIDE_NUM
import org.sil.storyproducer.model.SlideType
import org.sil.storyproducer.model.UploadState
import org.sil.storyproducer.model.Workspace
import org.sil.storyproducer.tools.Network.VolleySingleton
import org.sil.storyproducer.tools.Network.paramStringRequest
import org.sil.storyproducer.tools.file.getStoryChildInputStream
import org.sil.storyproducer.tools.file.getStoryUri
import org.sil.storyproducer.tools.media.AudioPlayer
import org.sil.storyproducer.tools.media.MediaHelper
import org.sil.storyproducer.tools.toolbar.PlayBackRecordingToolbar
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

fun getPhoneId(context: Context): String {
    return Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
}

fun sendProjectSpecificRequest(
    context: Context,
    relativeUrl: String,
    onSuccess: (JSONObject) -> Unit,
    onFailure: (VolleyError) -> Unit,
    params: HashMap<String, String> = HashMap()) {

    params["Key"] = context.getString(R.string.api_token)
    params["PhoneId"] = getPhoneId(context)
    val url = Workspace.getRoccUrlPrefix(context) + relativeUrl
    val req = object : paramStringRequest(Method.POST, url, params, {
        Log.i("LOG_VOLLEY", it)
        var jsonObject = JSONObject(it)
        onSuccess(jsonObject)
    }, {
        Log.e("LOG_VOLLEY", "HIT ERROR")
        Log.e("LOG_VOLLEY", it.toString())
        onFailure(it)
    }) {
        override fun getParams(): Map<String, String> {
            return this.mParams
        }
    }
    VolleySingleton.getInstance(context.applicationContext).addToRequestQueue(req)
}

// TODO @pwhite: Perhaps this function is more appropriate in another file, but
// hey, code is code, and it semantically doesn't matter where we put it. It
// matters for discoverability and convenience though, so worth thinking about
// once it is used more.
fun sendSlideSpecificRequest(
    context: Context,
    slideNumber: Int,
    relativeUrl: String,
    content: String,
    onSuccess: (JSONObject) -> Unit,
    onFailure: (VolleyError) -> Unit,
    js: HashMap<String, String> = HashMap()) {

    if (Workspace.activeStory.remoteId != null) {
        js["StoryId"] = Workspace.activeStory.remoteId.toString()
    } else {
        js["TemplateTitle"] = Workspace.activeStory.title
    }
    js["SlideNumber"] = slideNumber.toString()
    js["Data"] = content
    sendProjectSpecificRequest(context, relativeUrl, {
        val newStoryId = it.getInt("StoryId")
        Log.e("@pwhite", "Received id $newStoryId")
        if (Workspace.activeStory.remoteId == null) {
          Log.i("@pwhite", "Setting active story id from null to $newStoryId")
          Workspace.activeStory.remoteId = newStoryId
        } else {
          Log.e("SanityCheck", "Response id ($newStoryId) should be the same story id as stored (${Workspace.activeStory.remoteId})")
        }
        onSuccess(it)
    }, onFailure, js)
}

/**
 * Created by annmcostantino on 1/14/2018.
 *
 * An interface for doing back translations on the whole story. There is an image and a seekbar
 * which provide a UI for watching the video with both slides and audio. There is also a recording
 * toolbar for recording and uploading audio. It is us
 */


class WholeStoryBackTranslationFragment : Fragment(), PlayBackRecordingToolbar.ToolbarMediaListener {

    class DraftSlide(val slideNum: Int, val duration: Int, val startTime: Int, val filename: String) {}

    private lateinit var wholeStoryImageView: ImageView
    private lateinit var playButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var uploadButton: ImageButton

    private lateinit var greenCheckmark: VectorDrawableCompat
    private lateinit var grayCheckmark: VectorDrawableCompat
    private lateinit var yellowCheckmark: VectorDrawableCompat

    private var mSeekBarTimer = Timer()
    private var draftPlayer: AudioPlayer = AudioPlayer()
    private var seekbarStartTime: Long = -1

    private var isVolumeOn = true

    private var recordingToolbar: PlayBackRecordingToolbar = PlayBackRecordingToolbar()

    private var currentSlideIndex: Int = 0
    private val translatedSlides: MutableList<DraftSlide> = ArrayList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val rootView = inflater.inflate(R.layout.activity_whole_story, container, false)

        val bundle = Bundle()
        bundle.putInt(SLIDE_NUM, 0)
        recordingToolbar.arguments = bundle
        childFragmentManager.beginTransaction().replace(R.id.toolbar_for_recording_toolbar, recordingToolbar).commit()
        recordingToolbar.keepToolbarVisible()

        wholeStoryImageView = rootView.findViewById(R.id.fragment_image_view)
        playButton = rootView.findViewById(R.id.fragment_reference_audio_button)
        seekBar = rootView.findViewById(R.id.videoSeekBar)
        uploadButton = rootView.findViewById(R.id.upload_audio_botton)

        // TODO @pwhite: This state machine of switching between green, gray,
        // and yellow checkmarks is the same between the whole story and single
        // slide backtranslation, and perhaps even between text messages. It is likely worth abstracting this somehow.
        greenCheckmark = VectorDrawableCompat.create(resources, R.drawable.ic_checkmark_green, null)!!
        grayCheckmark = VectorDrawableCompat.create(resources, R.drawable.ic_checkmark_gray, null)!!
        yellowCheckmark = VectorDrawableCompat.create(resources, R.drawable.ic_checkmark_yellow, null)!!
        uploadButton.background = when (Workspace.activeStory.wholeStoryBackTranslationUploadState) {
            UploadState.UPLOADED -> greenCheckmark
            UploadState.NOT_UPLOADED -> grayCheckmark
            UploadState.UPLOADING -> yellowCheckmark
        }

        uploadButton.setOnClickListener {
            when (Workspace.activeStory.wholeStoryBackTranslationUploadState) {
                UploadState.UPLOADED -> Toast.makeText(context!!, "Selected recording already uploaded", Toast.LENGTH_SHORT).show()
                UploadState.NOT_UPLOADED -> {
                    val audioRecording = Workspace.activeStory.wholeStoryBackTAudioFile
                    if (audioRecording != null) {
                        Workspace.activeStory.wholeStoryBackTranslationUploadState = UploadState.UPLOADING
                        uploadButton.background = yellowCheckmark
                        Toast.makeText(context!!, "Uploading audio", Toast.LENGTH_SHORT).show()
                        val input = getStoryChildInputStream(context!!, audioRecording.fileName)
                        val audioBytes = IOUtils.toByteArray(input)
                        val byteString = android.util.Base64.encodeToString(audioBytes, android.util.Base64.DEFAULT)
                        val slideCount = Workspace.activeStory.slides.size
                        val js = HashMap<String, String>()
                        js["IsWholeStory"] = "true"
                        sendSlideSpecificRequest(context!!, slideCount, getString(R.string.url_upload_audio), byteString, {
                            Toast.makeText(context!!, R.string.audio_Sent, Toast.LENGTH_SHORT).show()
                            Workspace.activeStory.wholeStoryBackTranslationUploadState = UploadState.UPLOADED
                            uploadButton.background = greenCheckmark
                        }, {
                            Toast.makeText(context!!, R.string.audio_Send_Failed, Toast.LENGTH_SHORT).show()
                            Workspace.activeStory.wholeStoryBackTranslationUploadState = UploadState.NOT_UPLOADED
                            uploadButton.background = grayCheckmark
                        }, js)
                    } else {
                        Toast.makeText(context!!, "No recording found", Toast.LENGTH_SHORT).show()
                    }
                }
                UploadState.UPLOADING -> {
                    uploadButton.background = yellowCheckmark
                    Toast.makeText(context!!, "Upload already in progress", Toast.LENGTH_SHORT).show()
                }
            }
        }

        uploadButton.setOnLongClickListener {
            when (Workspace.activeStory.wholeStoryBackTranslationUploadState) {
                UploadState.UPLOADING -> {
                    Workspace.activeStory.wholeStoryBackTranslationUploadState = UploadState.NOT_UPLOADED
                    Toast.makeText(context!!, "Cancelling upload", Toast.LENGTH_SHORT).show()
                    uploadButton.background = grayCheckmark
                }
                UploadState.UPLOADED -> {
                    Workspace.activeStory.wholeStoryBackTranslationUploadState = UploadState.NOT_UPLOADED
                    Toast.makeText(context!!, "Ignoring previous upload", Toast.LENGTH_SHORT).show()
                    uploadButton.background = grayCheckmark
                }
                UploadState.NOT_UPLOADED -> Toast.makeText(context!!, "There have been no uploads yet", Toast.LENGTH_SHORT).show()
            }
            true
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var wasPlayingBeforeTouch = false
            override fun onStopTrackingTouch(sBar: SeekBar) {
                if (wasPlayingBeforeTouch) {
                    // Always start at the beginning of the slide.
                    if (currentSlideIndex < translatedSlides.size) {
                        seekBar.progress = translatedSlides[currentSlideIndex].startTime
                    }
                    playStoryAudio()
                }
            }

            override fun onStartTrackingTouch(sBar: SeekBar) {
                wasPlayingBeforeTouch = draftPlayer.isAudioPlaying
            }

            override fun onProgressChanged(sBar: SeekBar, progress: Int, fromUser: Boolean) {
                setSlideFromSeekbar()
            }
        })

        val volumeSwitch = rootView.findViewById<Switch>(R.id.volumeSwitch)
        volumeSwitch.isChecked = true
        volumeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isVolumeOn = if (isChecked) {
                draftPlayer.setVolume(1.0f)
                true
            } else {
                draftPlayer.setVolume(0.0f)
                false
            }
        }

        //get story audio duration
        var lastEndTime = 0
        Workspace.activeStory.slides.forEachIndexed { slideNum, slide ->
            // Don't play the copyright translatedSlides.
            if (slide.slideType == SlideType.FRONTCOVER || slide.slideType == SlideType.NUMBEREDPAGE) {
                val filename = slide.draftRecordings.selectedFile?.fileName
                if (filename != null) {
                    val duration = (MediaHelper.getAudioDuration(context!!, getStoryUri(filename)!!) / 1000).toInt()
                    val startTime = lastEndTime
                    lastEndTime = startTime + duration
                    translatedSlides.add(DraftSlide(slideNum, duration, startTime, filename))
                }
            }
        }

        seekBar.max = if (translatedSlides.isNotEmpty()) {
            val lastSlide = translatedSlides.last()
            lastSlide.startTime + lastSlide.duration
            translatedSlides.last().startTime
        } else {
            0
        }
        seekBar.progress = 0
        setSlideFromSeekbar()

        return rootView
    }

    override fun onPause() {
        super.onPause()
        pauseStoryAudio()
        draftPlayer.release()
    }

    override fun onResume() {
        super.onResume()
        draftPlayer = AudioPlayer()
        draftPlayer.onPlayBackStop(MediaPlayer.OnCompletionListener {
            if (draftPlayer.isAudioPrepared) {
                if (currentSlideIndex >= translatedSlides.size - 1) { //is it the last slide?
                    //at the end of video so special case
                    pauseStoryAudio()
                } else {
                    //just play the next slide!
                    seekBar.progress = translatedSlides[currentSlideIndex + 1].startTime
                    playStoryAudio()
                }
            }
        })

        val currentActivity = activity!!
        mSeekBarTimer = Timer()
        mSeekBarTimer.schedule(object : TimerTask() {
            override fun run() {
                currentActivity.runOnUiThread {
                    if (recordingToolbar.isRecording || recordingToolbar.isAudioPlaying) {
                        seekBar.progress = minOf((System.currentTimeMillis() - seekbarStartTime).toInt(), seekBar.max)
                    } else if (draftPlayer.isAudioPrepared) {
                        seekBar.progress = translatedSlides[currentSlideIndex].startTime + draftPlayer.currentPosition
                    } else {
                        seekBar.progress = 0
                    }
                }
            }
        }, 0, 33)

        setSlideFromSeekbar()
    }

    private fun setSlideFromSeekbar() {
        if (translatedSlides.isNotEmpty()) {
            val time = seekBar.progress
            var slideIndexBeforeSeekBar = translatedSlides.indexOfLast { it.startTime <= time }
            if (slideIndexBeforeSeekBar != currentSlideIndex || !draftPlayer.isAudioPrepared) {
                currentSlideIndex = slideIndexBeforeSeekBar
                val slide = translatedSlides[currentSlideIndex]
                PhaseBaseActivity.setPic(context!!, wholeStoryImageView, slide.slideNum)
                draftPlayer.setStorySource(context!!, slide.filename)
            }
        }
    }

    override fun onStoppedToolbarMedia() {
        seekBar.progress = 0
        setSlideFromSeekbar()
    }

    override fun onStartedToolbarMedia() {
        pauseStoryAudio()
        seekBar.progress = 0
        currentSlideIndex = 0
        //This gets the progress bar to show the right time.
        seekbarStartTime = System.currentTimeMillis()
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val item = menu.getItem(0)
        item.setIcon(R.drawable.ic_school_white_48dp)
    }

    /**
     * Plays the audio
     */
    internal fun playStoryAudio() {
        recordingToolbar.stopToolbarMedia()
        setSlideFromSeekbar()
        draftPlayer.pauseAudio()
        seekbarStartTime = System.currentTimeMillis()
        draftPlayer.setVolume(if (isVolumeOn) 1.0f else 0.0f) //set the volume on or off based on the boolean
        draftPlayer.playAudio()
        playButton.setImageResource(R.drawable.ic_pause_white_48dp)
    }

    /**
     * helper function for pausing the video
     */
    private fun pauseStoryAudio() {
        draftPlayer.pauseAudio()
        playButton.setImageResource(R.drawable.ic_play_arrow_white_48dp)
    }
}
