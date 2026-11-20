package com.gying.movie.dto;

import lombok.Data;

@Data
public class GyingWeeklyTransferSchedule {
    private String typeCode;
    private boolean enabled = true;
    private int intervalDays = 7;
    private int maxItems = 5;
    private String nextRunAt;
    private String lastRunAt;
    private String lastStatus;
    private Long lastTaskId;
}
