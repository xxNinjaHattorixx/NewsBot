package com.discord.newsbot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;

import javax.security.auth.login.LoginException;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.RateLimitedException;

public class NewsBot {

	// ----- 諸々設定する所 -----
	// discord
    private static final String DISCORD_TOKEN = "XXXXX";
    private static final String CHANNEL_ID = "XXXXX"; // 通知を飛ばしたいチャンネルID
    
    // RSSフィード
    private static final List<String> RSS_FEED_URLS = Arrays.asList(
    		// 通知を飛ばしたいRSSフィードを入力
    );
    
    private static final long CHECK_INTERVAL = 180000; // チェック頻度（3分）
    private static final String POSTED_LINKS_FILE = "postedLinks.txt"; // 重複通知を防ぐためのリスト
    private static final int MAX_POSTED_LINKS_SIZE = 90; // ファイル最大サイズ(件数)
    // ----- ここまで -----

    // 各オブジェクトの作成
    private static JDA jda; 
    private static Set<String> postedLinks = new LinkedHashSet<>(); //ポスト済みのニュースをまとめるセット

    // メイン処理ここから
    public static void main(String[] args) throws LoginException, InterruptedException, RateLimitedException, IOException {
        loadPostedLinks();
        // Q.なんで先に投稿済みニュースを確認するの？？
        // A.botがログインするとすぐにRSSフィードの読み込みをするから。
        // A.先にニュースを読み込んでおけば、JDAオブジェクト作成(Botログイン)でエラーが出てもその後はスムーズに動くようになるから。
        
        // Botをログインさせる
        jda = JDABuilder.createDefault(DISCORD_TOKEN).build();
        jda.awaitReady();
        System.out.println("Bot is ready!");

        // タイマーの感覚でニュースをフィードを処理
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkFeedsAndPost();
            }
        }, 0, CHECK_INTERVAL);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.gc();
            System.out.println("Garbage collection triggered on shutdown");
        }));
    }

    private static void checkFeedsAndPost() {
        for (String feedUrlString : RSS_FEED_URLS) {
            CompletableFuture.runAsync(() -> processFeed(feedUrlString));
        }
        managePostedLinksSize(); // セットの容量調整
    }

    private static void processFeed(String feedUrlString) { // フィードの処理(非同期)
        
    	// フィードの初期化
    	SyndFeed feed = null;
        XmlReader reader = null;
        try { // URLの作成、フィードの読み込み
            URL feedUrl = new URL(feedUrlString);
            SyndFeedInput input = new SyndFeedInput();
            reader = new XmlReader(feedUrl);
            feed = input.build(reader);

            TextChannel channel = jda.getTextChannelById(CHANNEL_ID); // 読み込んだニュースをポストする
            if (channel != null) {
                for (SyndEntry entry : feed.getEntries()) {
                    if (!postedLinks.contains(entry.getLink())) {
                        String message = entry.getTitle() + "\n" + entry.getLink(); // 1行目タイトル、2行目URLになるように整形
                        channel.sendMessage(message).queue(); // ポストの実施
                        postedLinks.add(entry.getLink()); // リンクをセットに追加
                        savePostedLink(entry.getLink()); // セットをファイルに保存
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally { // リソースのクリーンアップ。feedとreaderのクリア、readerが残っていたらクローズ
            feed = null;
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // 既に通知済みのニュースをチェック
    private static void loadPostedLinks() throws IOException {
        File file = new File(POSTED_LINKS_FILE);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    postedLinks.add(line);
                }
            }
        }
    }

    private static void savePostedLink(String link) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(POSTED_LINKS_FILE, true))) {
            writer.write(link);
            writer.newLine();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void managePostedLinksSize() {
        if (postedLinks.size() > MAX_POSTED_LINKS_SIZE) {
            Iterator<String> iterator = postedLinks.iterator(); // iterator(イテレータ)：集合の要素に順番にアクセスするオブジェクト
            while (iterator.hasNext() && postedLinks.size() > (MAX_POSTED_LINKS_SIZE - 20)) { // 上限値-20になるまで実行
                iterator.next();
                iterator.remove();
            }
        }
    }
}
