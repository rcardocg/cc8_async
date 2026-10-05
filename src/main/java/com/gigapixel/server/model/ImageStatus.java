package com.gigapixel.server.model;

import java.util.List;

public record ImageStatus(String imageId, String state, long processedTiles, long totalTiles,
                          Integer currentLevel, List<Integer> completedLevels, String sourceState,
                          String message, String error) { }
