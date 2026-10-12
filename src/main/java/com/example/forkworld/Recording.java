package com.example.forkworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A tick-exact input sequence plus the start state it is valid from. */
public final class Recording {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String dimension;
    public double x, y, z;
    public float yaw, pitch;
    public int startSlot;
    public List<InputFrame> frames = new ArrayList<>();

    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(this));
    }

    public static Recording load(Path path) throws IOException {
        return GSON.fromJson(Files.readString(path), Recording.class);
    }
}
