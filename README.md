# And-VerifyCameraPreviewMatrix
TextureViewと、CameraPreviewのサイズが合ってないとき、TextureViewに、何の値のMatrixを設定すればいいのかを検証したコード。

画面は、縦画面固定。
``` C++:メモリリーク検出(VisuslStudioのみ)
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
TextureViewのサイズは、画面サイズと同じ。
