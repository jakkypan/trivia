# MediaMuxer和MediaExtractor原理和基本操作

# > MediaMuxer

MediaMuxer类主要用于将音频和视频数据进行混合生成多媒体文件：

> MediaMuxer facilitates muxing elementary streams. Currently supports mp4 or webm file as the output and at most one audio and/or one video elementary stream. MediaMuxer does not support muxing B-frames.

可以看到，MediaMuxer最多仅支持一个视频track和一个音频track，所以如果有多个音频track可以先把它们混合成为一个音频track然后再使用MediaMuxer封装到mp4容器中。并且目前只支持MP4和webm的视频格式。

## 使用示例

```java
MediaMuxer muxer = new MediaMuxer("temp.mp4", OutputFormat.MUXER_OUTPUT_MPEG_4);
 // More often, the MediaFormat will be retrieved from MediaCodec.getOutputFormat()
 // or MediaExtractor.getTrackFormat().
 MediaFormat audioFormat = new MediaFormat(...);
 MediaFormat videoFormat = new MediaFormat(...);
 int audioTrackIndex = muxer.addTrack(audioFormat);
 int videoTrackIndex = muxer.addTrack(videoFormat);
 ByteBuffer inputBuffer = ByteBuffer.allocate(bufferSize);
 boolean finished = false;
 BufferInfo bufferInfo = new BufferInfo();

 muxer.start();
 while(!finished) {
   // getInputBuffer() will fill the inputBuffer with one frame of encoded
   // sample from either MediaCodec or MediaExtractor, set isAudioSample to
   // true when the sample is audio data, set up all the fields of bufferInfo,
   // and return true if there are no more samples.
   finished = getInputBuffer(inputBuffer, isAudioSample, bufferInfo);
   if (!finished) {
     int currentTrackIndex = isAudioSample ? audioTrackIndex : videoTrackIndex;
     muxer.writeSampleData(currentTrackIndex, inputBuffer, bufferInfo);
   }
 };
 muxer.stop();
 muxer.release();
```

使用过程介绍：

1. 生成MediaMuxer对象

通过new MediaMuxer(String path, int format)指定视频文件输出路径和文件格式：

```java
MediaMuxer mMediaMuxer = new MediaMuxer(mOutputVideoPath,
MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
```

2. addTrack

addTrack(MediaFormat format)，添加媒体通道，传入MediaFormat对象，通常从MediaExtractor或者MediaCodec中获取，也可以自己创建。

3. 调用start函数

```java
MediaMuxer.start();
```

4. 写入数据

调用MediaMuxer.writeSampleData()向mp4文件中写入数据了。每次只能添加一帧视频数据或者单个Sample的音频数据，需要BufferInfo对象作为参数。

```java
BufferInfo info = new BufferInfo();
info.offset = 0;
info.size = sampleSize;
info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
info.presentationTimeUs = mVideoExtractor.getSampleTime();
mMediaMuxer.writeSampleData(videoTrackIndex, buffer, info);
```

* info.size: 必须填入数据的大小
* info.flags: 需要给出是否为同步帧/关键帧
* info.presentationTimeUs: 必须给出正确的时间戳，注意单位是 us，第二次getSampleTime()和首次getSampleTime()的时间差

5. 释放关闭资源

结束写入后关闭以及释放资源：

```java
MediaMuxer.stop();
MediaMuxer.release();
```

# > MediaExtractor

MediaExtractor主要用于多媒体文件的音视频数据的分离，即解封装。

基本操作：

```java
extractor = new MediaExtractor();
extractor.setDataSource("/sdcard/test.mp4");
 
dumpFormat(extractor);
 
private void dumpFormat(MediaExtractor extractor) {                      
    int count = extractor.getTrackCount();                               
    Log.i(TAG, "playVideo: track count: " + count);                      
    for (int i = 0; i < count; i++) {                                    
        MediaFormat format = extractor.getTrackFormat(i);                
        Log.i(TAG, "playVideo: track " + i + ":" + getTrackInfo(format));
 
		String mime = format.getString(MediaFormat.KEY_MIME);
		if(mime.startsWith("Video/")){
			videoTrackIndex = i;
			mMediaExtractor.selectTrack(videoTrackIndex);
			while(true) {
				int sampleSize = mMediaExtractor.readSampleData(buffer, 0);
				if(sampleSize < 0){
					break;
				}
				mMediaExtractor.advance(); //移动到下一帧
			}
			mMediaExtractor.release(); //读取结束后，要记得释放资源
		} else if(mime.startsWith("audio/")){
			audioTrackIndex = 0;
		}
    }                                                                    
}
```

# 两者一起的例子

```java
private void muxingAudioAndVideo() throws IOException {
　　MediaMuxer mMediaMuxer = new MediaMuxer(mOutputVideoPath,
　　MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
　　
　　// 视频的MediaExtractor
　　MediaExtractor mVideoExtractor = new MediaExtractor();
　　mVideoExtractor.setDataSource(mVideoPath);
　　int videoTrackIndex = -1;
　　for (int i = 0; i < mVideoExtractor.getTrackCount(); i++) {
　　	MediaFormat format = mVideoExtractor.getTrackFormat(i);
　　	if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
　　		mVideoExtractor.selectTrack(i);
　　		videoTrackIndex = mMediaMuxer.addTrack(format);
　　		break;
　　	}
　　}
　　
　　// 音频的MediaExtractor
　　MediaExtractor mAudioExtractor = new MediaExtractor();
　　mAudioExtractor.setDataSource(mAudioPath);
　　int audioTrackIndex = -1;
　　for (int i = 0; i < mAudioExtractor.getTrackCount(); i++) {
　　	MediaFormat format = mAudioExtractor.getTrackFormat(i);
　　	if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
　　		mAudioExtractor.selectTrack(i);
　　		audioTrackIndex = mMediaMuxer.addTrack(format);
　　	}
　　}
　　
　　// 添加完所有轨道后start
　　mMediaMuxer.start();
　　
　　// 封装视频track
　　if (-1 != videoTrackIndex) {
　　	MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
　　	info.presentationTimeUs = 0;
　　	ByteBuffer buffer = ByteBuffer.allocate(100 * 1024);
　　	while (true) {
　　		int sampleSize = mVideoExtractor.readSampleData(buffer, 0);
　　		if (sampleSize < 0) {
　　			break;
　		 }
　　	    info.offset = 0;
　　      info.size = sampleSize;
　　      info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
	  　　info.presentationTimeUs = mVideoExtractor.getSampleTime();
	  　　mMediaMuxer.writeSampleData(videoTrackIndex, buffer, info);
	  　　// 移动到下一帧
	  　　mVideoExtractor.advance();
　　}
　　}
　　// 封装音频track
　　if (-1 != audioTrackIndex) {
　　	MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
　　	info.presentationTimeUs = 0;
　　	ByteBuffer buffer = ByteBuffer.allocate(100 * 1024);
　　	while (true) {
　　		int sampleSize = mAudioExtractor.readSampleData(buffer, 0);
　　		if (sampleSize < 0) {
　　			break;
　　		}
　　		info.offset = 0;
　　		info.size = sampleSize;
　　		info.flags = MediaCodec.BUFFER_FLAG_SYNC_FRAME;
　　		info.presentationTimeUs = mAudioExtractor.getSampleTime();
　　		mMediaMuxer.writeSampleData(audioTrackIndex, buffer, info);
　　		mAudioExtractor.advance();
　　	}
　　}
　　
　　// 释放MediaExtractor
　　mVideoExtractor.release();
　　mAudioExtractor.release();
　　
　　// 释放MediaMuxer
　　mMediaMuxer.stop();
　　mMediaMuxer.release();
}


// MediaExtractor的接口比较简单，首先通过setDataSource()设置数据源，数据源可以是本地文件地址，也可以是网络地址：
MediaExtractor mVideoExtractor = new MediaExtractor();
mVideoExtractor.setDataSource(mVideoPath);
然后可以通过getTrackFormat(int index)来获取各个track的MediaFormat，通过MediaFormat来获取track的详细信息，如：MimeType、分辨率、采样频率、帧率等等：
for (int i = 0; i < mVideoExtractor.getTrackCount(); i++) {
　　MediaFormat format = mVideoExtractor.getTrackFormat(i);
　　// 获取到track的详细信息后，通过selectTrack(int index)选择指定的通道：
　　if (format.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
	　　mVideoExtractor.selectTrack(i);
　　	  break;
　　}
　　// 指定通道之后就可以从MediaExtractor中读取数据了：
　　while (true) {
	　　int sampleSize = mVideoExtractor.readSampleData(buffer, 0);
　　	if (sampleSize < 0) {
　　		break;
　　	}
　　	// do something
　　	mVideoExtractor.advance(); // 移动到下一帧
　　}
}
// 在读取结束之后，记得释放资源：
mVideoExtractor.release();
```
