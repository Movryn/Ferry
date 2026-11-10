package xyz.movryn.ferry;

import java.nio.file.Path;
import java.util.List;

public interface Host {
    Path folder();

    void info(String message);

    void warn(String message);

    void broadcast(Line line);

    void runCommand(String command);

    List<String> onlinePlayers();

    int maxPlayers();
}
