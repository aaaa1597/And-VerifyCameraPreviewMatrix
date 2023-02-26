# And-VerifyCameraPreviewMatrix
TextureViewと、CameraPreviewのサイズが合ってないとき、TextureViewに、何の値のMatrixを設定すればいいのかを検証したコード。

- キモはこんな感じ。
   1. 画面は、縦画面固定。
   ``` AndroidManifest.xml
        <activity
            android:name=".MainActivity"
            android:screenOrientation="portrait"
            android:exported="true">
   ```
   ※回転する設定でも、問題ないはず(確認してないけど)。

   2. TextureViewのサイズは、画面サイズと同じ。
   ``` fragment_main.xml
    <TextureView
        android:id="@+id/tvw_preview"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        tools:ignore="RelativeOverlap" />
   ```
   ※match_parentで、領域いっぱいになる様にしている。<br/>
   ※Pixel 4aだと、1080 x 2340[アスペクト比:2.166667]。

   3. カメラのサポートサイズからTextureViewのサイズとアスペクト比に一番近いカメラサイズを選ぶ。
   ``` fragment_main.xml
   ・
   2560 x 1280[アスペクト比2.000000]
   ・
   ・
   ```
   ※Pixel 4aだと、2560 x 1280[アスペクト比2.000000]。
