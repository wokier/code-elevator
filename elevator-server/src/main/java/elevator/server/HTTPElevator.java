package elevator.server;

import elevator.Command;
import elevator.Direction;
import elevator.User;
import elevator.engine.ElevatorEngine;
import elevator.exception.ElevatorIsBrokenException;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.net.URLEncoder.encode;
import static java.text.MessageFormat.format;

class HTTPElevator implements ElevatorEngine {

    private final URL server;
    private final ExecutorService executor;
    private final URLStreamHandler urlStreamHandler;
    private final URL nextCommand;
    private final URL userHasEntered;
    private final URL userHasExited;
    private final URL reset;
    private final String defaultCharset;
    private final Pattern errorStatusMessage;

    private String transportErrorMessage;

    HTTPElevator(URL server, ExecutorService executor) throws MalformedURLException {
        this(server, executor, null);
    }

    HTTPElevator(URL server, ExecutorService executor, URLStreamHandler urlStreamHandler) throws MalformedURLException {
        this.executor = executor;
        this.urlStreamHandler = urlStreamHandler;
        this.server = new URL(server, "", urlStreamHandler);
        this.nextCommand = new URL(server, "nextCommand", urlStreamHandler);
        this.userHasEntered = new URL(server, "userHasEntered", urlStreamHandler);
        this.userHasExited = new URL(server, "userHasExited", urlStreamHandler);
        this.reset = new URL(server, "reset", urlStreamHandler);
        this.defaultCharset = Charset.defaultCharset().name();
        this.errorStatusMessage = Pattern.compile("Server returned HTTP response code: (\\d+).+");
    }

    @Override
    public ElevatorEngine call(Integer atFloor, Direction to) throws ElevatorIsBrokenException {
        checkTransportError();
        System.out.println(server.toString() + "/call?atFloor=" + atFloor + "&to=" + to.toString());
        httpGet("call?atFloor=" + atFloor + "&to=" + to);
        return this;
    }

    @Override
    public ElevatorEngine go(Integer floorToGo) throws ElevatorIsBrokenException {
        checkTransportError();
        System.out.println(server.toString() + "/go?floorToGo=" + floorToGo);
        httpGet("go?floorToGo=" + floorToGo);
        return this;
    }

    @Override
    public Command nextCommand() throws ElevatorIsBrokenException {
        checkTransportError();
        StringBuilder out = new StringBuilder(nextCommand.toString());
        String commandFromResponse = "";
        try {
            URLConnection urlConnection = getUrlConnection(nextCommand);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()))) {
                commandFromResponse = in.readLine();
                transportErrorMessage = null;
                Command command = Command.valueOf(commandFromResponse);
                out.append(" ").append(command);
                return command;
            }
        } catch (IllegalArgumentException e) {
            out.append(" ").append(commandFromResponse);
            throw new ElevatorIsBrokenException("Command \"" + commandFromResponse + "\" is not a valid command; valid commands are [UP|DOWN|OPEN|CLOSE|NOTHING] with case sensitive");
        } catch (IOException e) {
            transportErrorMessage = createErrorMessage(nextCommand, e);
            throw new ElevatorIsBrokenException(transportErrorMessage);
        } finally {
            System.out.println(out.toString());
        }
    }

    @Override
    public ElevatorEngine userHasEntered(User user) throws ElevatorIsBrokenException {
        checkTransportError();
        System.out.println(userHasEntered);
        httpGet(userHasEntered);
        return this;
    }

    @Override
    public ElevatorEngine userHasExited(User user) throws ElevatorIsBrokenException {
        checkTransportError();
        System.out.println(userHasExited);
        httpGet(userHasExited);
        return this;
    }

    @Override
    public ElevatorEngine reset(String cause) throws ElevatorIsBrokenException {
        // do not check transport error
        String encodedCause = urlEncode(cause);
        System.out.println(reset + "?cause=" + encodedCause);
        httpGet(reset + "?cause=" + encodedCause);
        return this;
    }

    private void httpGet(String pathAndParameters) throws ElevatorIsBrokenException {
        try {
            httpGet(new URL(server, pathAndParameters, urlStreamHandler));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private void httpGet(final URL url) throws ElevatorIsBrokenException {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    URLConnection urlConnection = getUrlConnection(url);
                    try (InputStream in = urlConnection.getInputStream()) {
                        transportErrorMessage = null;
                    }
                } catch (IOException e) {
                    transportErrorMessage = createErrorMessage(url, e);
                }
            }
        });
    }

    private URLConnection getUrlConnection(URL url) throws IOException {
        URLConnection urlConnection = url.openConnection();
        urlConnection.setConnectTimeout(1000);
        urlConnection.setReadTimeout(1000);
        return urlConnection;
    }

    private void checkTransportError() {
        if (transportErrorMessage != null) {
            throw new ElevatorIsBrokenException(transportErrorMessage);
        }
    }

    private String urlEncode(String cause) {
        try {
            return encode(cause, defaultCharset);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private String createErrorMessage(URL url, IOException e) {
        if (e instanceof FileNotFoundException) {
            return format("Resource \"{0}\" is not found", urlWithoutQuery(url));
        }

        if (e instanceof UnknownHostException) {
            return format("IP address of \"{0}\" could not be determined", e.getMessage());
        }

        Matcher matcher = errorStatusMessage.matcher(e.getMessage());
        if (matcher.matches()) {
            return format("Server returned HTTP response code: {0} for URL: {1}", matcher.group(1), urlWithoutQuery(url));
        }

        return e.getMessage();
    }

    private String urlWithoutQuery(URL url) {
        return format("{0}://{1}{2}", url.getProtocol(), url.getAuthority(), url.getPath());
    }

}
