package ru.pulsedoma.bootstrap;

import org.springframework.stereotype.Component;
import ru.pulsedoma.common.BusinessException;
import java.net.*;
import java.util.Locale;

/** MAX-provided URLs only. No redirects and never forward the bot token to media hosts. */
@Component
public class MaxBotMedia {
    public record File(byte[] bytes,String mime) {}
    public File download(String url) {
        try {
            URI uri=URI.create(url);String host=uri.getHost();
            if(!allowed(uri))throw new IllegalArgumentException();
            for(InetAddress address:InetAddress.getAllByName(host))
                if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()
                    ||address.isSiteLocalAddress()||address.isMulticastAddress())throw new IllegalArgumentException();
            var connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(5000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            byte[] bytes;
            try {
                if(connection.getResponseCode()!=200 || connection.getContentLengthLong()>10*1024*1024)throw new IllegalArgumentException();
                try(var input=connection.getInputStream()) {bytes=input.readNBytes(10*1024*1024+1);}
            } finally {connection.disconnect();}
            if(bytes.length>10*1024*1024)throw new IllegalArgumentException();
            String mime=bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255?"image/jpeg":
                bytes.length>=8&&java.util.Arrays.equals(java.util.Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png":
                bytes.length>=12&&new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")
                &&new String(bytes,Math.max(0,bytes.length-1024),Math.min(1024,bytes.length),java.nio.charset.StandardCharsets.ISO_8859_1).contains("%%EOF")?"application/pdf":null;
            if(mime==null)throw new IllegalArgumentException();
            return new File(bytes,mime);
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new BusinessException("BOT_FILE_UNAVAILABLE","Не удалось принять файл. Нужен PDF, JPEG или PNG до 10 МБ. Попробуйте снова или приложите его в мини-приложении.");
        }
    }
    public static boolean allowed(URI uri) {
        String h=uri.getHost()==null?"":uri.getHost().toLowerCase(Locale.ROOT);
        return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)
            &&java.util.List.of("oneme.ru","max.ru","okcdn.ru").stream().anyMatch(domain->h.endsWith("."+domain));
    }
}
