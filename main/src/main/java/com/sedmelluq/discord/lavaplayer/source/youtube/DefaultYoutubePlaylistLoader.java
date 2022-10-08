package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.PBJUtils;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.BROWSE_CONTINUATION_PAYLOAD;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.BROWSE_PLAYLIST_PAYLOAD;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.BROWSE_URL;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.WATCH_URL_PREFIX;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.YOUTUBE_ORIGIN;
import static com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.COMMON;

public class DefaultYoutubePlaylistLoader implements YoutubePlaylistLoader {
  private volatile int playlistPageCount = 6;

  @Override
  public void setPlaylistPageCount(int playlistPageCount) {
    this.playlistPageCount = playlistPageCount;
  }

  @Override
  public AudioPlaylist load(HttpInterface httpInterface, String playlistId, String selectedVideoId,
                            Function<AudioTrackInfo, AudioTrack> trackFactory) {
    HttpPost post = new HttpPost(BROWSE_URL);
    StringEntity payload = new StringEntity(String.format(BROWSE_PLAYLIST_PAYLOAD, playlistId), "UTF-8");
    post.setEntity(payload);
    try (CloseableHttpResponse response = httpInterface.execute(post)) {
      HttpClientTools.assertSuccessWithContent(response, "playlist response");
      HttpClientTools.assertJsonContentType(response);

      JsonBrowser json = JsonBrowser.parse(response.getEntity().getContent());
      return buildPlaylist(httpInterface, json, selectedVideoId, trackFactory);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private AudioPlaylist buildPlaylist(HttpInterface httpInterface, JsonBrowser json, String selectedVideoId,
                                      Function<AudioTrackInfo, AudioTrack> trackFactory) throws IOException {

    String errorAlertMessage = findErrorAlert(json);

    if (errorAlertMessage != null) {
      throw new FriendlyException(errorAlertMessage, COMMON, null);
    }

    JsonBrowser playlistInfo = json
            .get("header")
            .get("playlistHeaderRenderer");
    JsonBrowser playlistName = playlistInfo.get("title").get("runs").index(0).get("text");
    JsonBrowser channel = playlistInfo.get("ownerText").get("runs").index(0);
    String channelName = channel.get("text").text();
    JsonBrowser channelId =  channel.get("navigationEndpoint").get("browseEndpoint").get("browseId");
    JsonBrowser thumbnail =  playlistInfo.get("playlistHeaderBanner").get("heroPlaylistThumbnailRenderer");

    JsonBrowser playlistVideoList = json
            .get("contents")
            .get("singleColumnBrowseResultsRenderer")
            .get("tabs")
            .index(0)
            .get("tabRenderer")
            .get("content")
            .get("sectionListRenderer")
            .get("contents")
            .index(0)
            .get("playlistVideoListRenderer");

    List<AudioTrack> tracks = new ArrayList<>();
    String continuationsToken = extractPlaylistTracks(playlistVideoList, tracks, trackFactory);
    int loadCount = 0;
    int pageCount = playlistPageCount;

    // Also load the next pages, each result gives us a JSON with separate values for list html and next page loader html
    while (continuationsToken != null && ++loadCount < pageCount) {
      HttpPost post = new HttpPost(BROWSE_URL);
      StringEntity payload = new StringEntity(String.format(BROWSE_CONTINUATION_PAYLOAD, continuationsToken), "UTF-8");
      post.setEntity(payload);
      try (CloseableHttpResponse response = httpInterface.execute(post)) {
        HttpClientTools.assertSuccessWithContent(response, "playlist response");

        JsonBrowser continuationJson = JsonBrowser.parse(response.getEntity().getContent());

        JsonBrowser playlistVideoListPage = continuationJson.get("continuationContents")
                .get("playlistVideoListContinuation");

        continuationsToken = extractPlaylistTracks(playlistVideoListPage, tracks, trackFactory);
      }
    }

    if(tracks.isEmpty()) throw new FriendlyException("No videos in this playlist yet", COMMON, null);

    String image = (!thumbnail.isNull()) ? PBJUtils.getYouTubeThumbnail(thumbnail, tracks.get(0).getInfo().identifier) : tracks.get(0).getInfo().artworkUrl;

    return new BasicAudioPlaylist(
            (playlistName.isNull()) ? "YouTube" : playlistName.text(),
            channelName,
            YOUTUBE_ORIGIN + "/channel/" + (!channelId.isNull() ? channelId.text() : "UCBR8-60-B28hp2BmDPdntcQ") ,
            image,
            tracks,
            findSelectedTrack(tracks, selectedVideoId),
            false);
  }

  private String findErrorAlert(JsonBrowser jsonResponse) {
    JsonBrowser alerts = jsonResponse.get("alerts");

    if (!alerts.isNull()) {
      for (JsonBrowser alert : alerts.values()) {
        JsonBrowser alertInner = alert.get("alertRenderer");
        String type = alertInner.get("type").text();

        if ("ERROR".equals(type)) {
          JsonBrowser textObject = alertInner.get("text");

          String text;
          if (!textObject.get("simpleText").isNull()) {
            text = textObject.get("simpleText").text();
          } else {
            text = textObject.get("runs").values().stream()
                    .map(run -> run.get("text").text())
                    .collect(Collectors.joining());
          }

          return text;
        }
      }
    }

    return null;
  }

  private AudioTrack findSelectedTrack(List<AudioTrack> tracks, String selectedVideoId) {
    if (selectedVideoId != null) {
      for (AudioTrack track : tracks) {
        if (selectedVideoId.equals(track.getIdentifier())) {
          return track;
        }
      }
    }

    return null;
  }

  private String extractPlaylistTracks(JsonBrowser playlistVideoList, List<AudioTrack> tracks,
                                       Function<AudioTrackInfo, AudioTrack> trackFactory) {
    JsonBrowser contents = playlistVideoList.get("contents");
    if (contents.isNull()) return null;

    final List<JsonBrowser> playlistTrackEntries = contents.values();
    for (JsonBrowser track : playlistTrackEntries) {
      JsonBrowser item = track.get("playlistVideoRenderer");

      JsonBrowser shortBylineText = item.get("shortBylineText");

      // If the isPlayable property does not exist, it means the video is removed or private
      // If the shortBylineText property does not exist, it means the Track is Region blocked
      if (!item.get("isPlayable").isNull() && !shortBylineText.isNull()) {
        String videoId = item.get("videoId").text();
        JsonBrowser titleField = item.get("title");
        String title = Optional.ofNullable(titleField.get("simpleText").text())
                .orElse(titleField.get("runs").index(0).get("text").text());
        String author = shortBylineText.get("runs").index(0).get("text").text();
        JsonBrowser lengthSeconds = item.get("lengthSeconds");
        long duration = Units.secondsToMillis(lengthSeconds.asLong(Units.DURATION_SEC_UNKNOWN));

        AudioTrackInfo info = new AudioTrackInfo(title, author, duration, videoId, false,
            WATCH_URL_PREFIX + videoId, PBJUtils.getYouTubeThumbnail(item, videoId));

        tracks.add(trackFactory.apply(info));
      }
    }

    JsonBrowser continuations = playlistVideoList.get("continuations")
            .index(0)
            .get("nextContinuationData");
    String continuationsToken;
    if (!continuations.isNull()) {
      continuationsToken = continuations.get("continuation").text();
      return continuationsToken;
    }

    return null;
  }
}