package com.kroegerama.kaiteki.cache.example

import com.kroegerama.kaiteki.baseui.BaseActivity
import com.kroegerama.kaiteki.cache.ImageMagic
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
