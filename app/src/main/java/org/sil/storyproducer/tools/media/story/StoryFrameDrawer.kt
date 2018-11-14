package org.sil.storyproducer.tools.media.story

import android.content.Context
import android.graphics.*
import android.media.MediaFormat
import android.text.Layout
import android.util.Log
import org.sil.storyproducer.tools.file.getStoryImage

import org.sil.storyproducer.tools.media.MediaHelper
import org.sil.storyproducer.tools.media.graphics.TextOverlay
import org.sil.storyproducer.tools.media.pipe.PipedVideoSurfaceEncoder
import org.sil.storyproducer.tools.media.pipe.SourceUnacceptableException

import java.io.IOException

/**
 * This class knows how to draw the frames provided to it by [StoryMaker].
 */
internal class StoryFrameDrawer(private val context: Context, private val mVideoFormat: MediaFormat, private val mPages: Array<StoryPage>, private val mAudioTransitionUs: Long, slideCrossFadeUs: Long) : PipedVideoSurfaceEncoder.Source {
    private val xTime: Long //transition (cross fade) time

    private val mFrameRate: Int

    private val mWidth: Int
    private val mHeight: Int

    private val mBitmapPaint: Paint

    private var mCurrentTextOverlay: TextOverlay? = null
    private var mNextTextOverlay: TextOverlay? = null

    private var slideIndex = -1 //starts at -1 to allow initial transition
    private var slideAudioStart: Long = 0
    private var slideAudioEnd: Long = 0
    private var nSlideAudioEnd: Long = 0
    private val slideVisStart: Long
        get() {return if(slideIndex<=0){slideAudioStart} else {slideAudioStart-xTime/2}}
    private val slideXStart: Long  //beginning of the next transition
        get() {return if(slideIndex>=mPages.size-1){slideAudioEnd} else {slideAudioEnd-xTime/2}}
    private val slideXEnd: Long  //end of the next transition
        get() {return if(slideIndex>=mPages.size-1){slideAudioEnd} else {slideAudioEnd+xTime/2}}
    private val nSlideXEnd: Long  //end of the next transition
        get() {return if(slideIndex>=mPages.size-2){nSlideAudioEnd} else {nSlideAudioEnd+xTime/2}}
    private val slideVisDur: Long // the visible duration of the slide
        get() {return slideXEnd - slideVisStart}
    private val nSlideVisDur: Long // the visible duration of the next slide
        get() {return nSlideXEnd - slideXStart}

    private var mCurrentFrame = 0

    private var mIsVideoDone = false

    private var bitmaps: MutableMap<String,Bitmap?> = mutableMapOf()

    init {

        var correctedSlideTransitionUs = slideCrossFadeUs

        //mSlideTransition must never exceed the length of slides in terms of audio.
        //Pre-process pages and clip the slide transition time to fit in all cases.
        for (page in mPages) {
            val totalPageUs = page.audioDuration + mAudioTransitionUs
            if (correctedSlideTransitionUs > totalPageUs) {
                correctedSlideTransitionUs = totalPageUs
                Log.d(TAG, "Corrected slide transition from $slideCrossFadeUs to $correctedSlideTransitionUs")
            }
        }

        xTime = correctedSlideTransitionUs

        mFrameRate = mVideoFormat.getInteger(MediaFormat.KEY_FRAME_RATE)

        mWidth = mVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
        mHeight = mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)

        mBitmapPaint = Paint()
        mBitmapPaint.isAntiAlias = true
        mBitmapPaint.isFilterBitmap = true
        mBitmapPaint.isDither = true
    }

    override fun getMediaType(): MediaHelper.MediaType {
        return MediaHelper.MediaType.VIDEO
    }

    override fun getOutputFormat(): MediaFormat {
        return mVideoFormat
    }

    override fun isDone(): Boolean {
        return mIsVideoDone
    }

    @Throws(IOException::class, SourceUnacceptableException::class)
    override fun setup() {
        if (mPages.isNotEmpty()) {
            val nextPage = mPages[0]

            val nextText = nextPage.text
            mNextTextOverlay = TextOverlay(nextText)
            //Push text to bottom if there is a picture.
            mNextTextOverlay!!.setVerticalAlign(Layout.Alignment.ALIGN_OPPOSITE)
        }
    }

    override fun fillCanvas(canv: Canvas): Long {

        //[-|-page-1-|-| ]
        //           [ |-|-page-2-|-| ]
        //                        [ |-|-page-last-|-]
        // | | | (two bars) = transition time (xtime)
        // | | (one bar) = 1/2 xtime
        // --- (dash) sound playing from slide
        // Exclusive time + xtime/2 for first and last slide
        // "current page" is the page until it ends
        // "Next page" is growing in intensity for "xtime"
        // Visible time

        //Each time this is called, go forward 1/30 of a second.
        val cTime = MediaHelper.getTimeFromIndex(mFrameRate.toLong(), mCurrentFrame)

        if(cTime > slideXEnd){
            //go to the next slide
            slideIndex++

            if (slideIndex >= mPages.size) {
                mIsVideoDone = true
            } else {
                slideAudioStart = slideAudioEnd
                slideAudioEnd += mPages[slideIndex].getDuration(mAudioTransitionUs)
                mCurrentTextOverlay = mNextTextOverlay

                if (slideIndex + 1 < mPages.size) {
                    nSlideAudioEnd = slideAudioEnd + mPages[slideIndex + 1].getDuration(mAudioTransitionUs)

                    val nextText = mPages[slideIndex + 1].text
                    mNextTextOverlay = TextOverlay(nextText)
                    //Push text to bottom if there is a picture.
                    mNextTextOverlay!!.setVerticalAlign(Layout.Alignment.ALIGN_OPPOSITE)
                }
            }
        }

        drawFrame(canv, slideIndex, cTime - slideVisStart, slideVisDur,
                1f, mCurrentTextOverlay)

        if (cTime >= slideXStart) {
            val alpha = (cTime - slideXStart) / xTime.toFloat()
            drawFrame(canv, slideIndex + 1, cTime - slideXStart, nSlideVisDur,
                    alpha, mNextTextOverlay)
        }

        //clear image cache to save memory.
        if(slideIndex >= 1) {
            if (bitmaps.containsKey(mPages[slideIndex - 1].imRelPath)) {
                bitmaps.remove(mPages[slideIndex - 1].imRelPath)
            }
        }

        mCurrentFrame++

        return cTime
    }

    private fun drawFrame(canv: Canvas, pageIndex: Int, timeOffsetUs: Long, imgDurationUs: Long,
                          alpha: Float, overlay: TextOverlay?) {
        //In edge cases, draw a black frame with alpha value.
        if (pageIndex < 0 || pageIndex >= mPages.size) {
            canv.drawARGB((alpha * 255).toInt(), 0, 0, 0)
            return
        }

        val page = mPages[pageIndex]
        if(!bitmaps.containsKey(page.imRelPath)){
            bitmaps[page.imRelPath] = getStoryImage(context,page.imRelPath)
        }
        val bitmap = bitmaps[page.imRelPath]

        if (bitmap != null) {
            val kbfx = page.kenBurnsEffect

            val position = (timeOffsetUs / imgDurationUs.toDouble()).toFloat()

            val drawRect: RectF
            if (kbfx != null) {
                drawRect = kbfx.revInterpolate(position,mWidth,mHeight,bitmap.width,bitmap.height)
            } else {
                drawRect = RectF(0f, 0f, mWidth*1f, mHeight*1f)
            }

            mBitmapPaint.alpha = (alpha * 255).toInt()

            canv.drawBitmap(bitmap, null, drawRect, mBitmapPaint)
        } else {
            //If there is no picture, draw black background for text overlay.
            canv.drawARGB((alpha * 255).toInt(), 0, 0, 0)
        }

        if (overlay != null) {
            overlay.setAlpha(alpha)
            overlay.draw(canv)
        }
    }

    override fun close() {
        bitmaps.clear()
    }

    companion object {
        private val TAG = "StoryFrameDrawer"
    }
}
