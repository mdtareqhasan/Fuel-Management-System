
import java.net.http.*;
import java.net.URI;
import java.time.Duration;

public class Probe {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                .timeout(Duration.ofMillis(2000))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("HTTP " + response.statusCode());
        } catch (Exception e) {
            System.out.println("EXCEPTION CHAIN");
            Throwable current = e;
            int depth = 0;
            while (current != null && depth < 10) {
                System.out.println("  [" + depth + "] " + current.getClass().getName() + ": " + current.getMessage());
                current = current.getCause();
                depth++;
            }
        }
    }
}
