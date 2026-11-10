package xyz.movryn.ferry;

public enum Platform {
    DISCORD("Discord", "discord"),
    FLUXER("Fluxer", "fluxer");

    public final String label;
    final String key;

    Platform(String label, String key) {
        this.label = label;
        this.key = key;
    }
}
