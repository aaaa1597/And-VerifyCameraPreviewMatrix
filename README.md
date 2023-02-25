# And-VerifyCameraPreviewMatrix
TextureViewと、CameraPreviewのサイズが合ってないとき、TextureViewに、何の値のMatrixを設定すればいいのかを検証したコード。

画面は、縦画面固定。
``` AndroidManifest.xml
	・
	・
	・
        <activity
            android:name=".MainActivity"
            android:screenOrientation="portrait"
            android:exported="true">
            <intent-filter>
	・
	・
	・
}
```
※回転する様に設定しても、問題ないはず(確認してないけど)。

TextureViewのサイズは、画面サイズと同じ。
