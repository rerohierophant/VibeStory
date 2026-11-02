package com.vibestory.provider;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.containers.mp4.MP4Util;

public final class Mp4Media {
    private Mp4Media() {}
    public static int durationMs(byte[] bytes) {
        try (var channel=ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes))) {
            var movie=MP4Util.parseMovieChannel(channel);
            if(movie==null || movie.getVideoTrack()==null || movie.getTimescale()<=0) throw new IOException("缺少视频轨道或时基");
            long duration=Math.round(1000.0*movie.getDuration()/movie.getTimescale());
            if(duration<1 || duration>20000) throw new IOException("视频时长无效");
            return (int)duration;
        } catch(IOException | RuntimeException error) {
            throw new ProviderException("VIDEO_DOWNLOAD_INVALID","视频文件未通过 MP4 校验，可重查原任务",false,true);
        }
    }
}
