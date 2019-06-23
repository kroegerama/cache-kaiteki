package com.kroegerama.kaiteki.caching

import com.kroegerama.kaiteki.baseui.BaseActivity
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : BaseActivity() {

    override val layoutResource = R.layout.activity_main

    private val imageMagic by lazy { ImageMagic(this, lifecycle, debug = true) }

    override fun setupGUI() {
        imageMagic.loadImage("https://picsum.photos/800/1200?random=1337", ivImage)

        ivImage.setOnClickListener {
            imageMagic.loadImage("https://picsum.photos/800/1200?random=1337", ivImage, forceUpdate = true)
        }
    }
}
