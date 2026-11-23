package com.catchnyang.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** game_match 한 줄 = 끝난 판 하나. 시각은 UTC */
@Entity
@Table(name = "game_match")
public class GameMatchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_type", nullable = false, length = 10)
    private String matchType;

    @Column(name = "room_code", columnDefinition = "char(6)")
    private String roomCode;

    @Column(name = "rematch_of")
    private Long rematchOf;

    @Column(name = "started_at", nullable = false, columnDefinition = "datetime(3)")
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false, columnDefinition = "datetime(3)")
    private LocalDateTime endedAt;

    @Column(name = "winner_role", nullable = false, length = 10)
    private String winnerRole;

    @Column(name = "end_reason", nullable = false, length = 20)
    private String endReason;

    @Column(name = "final_avg", nullable = false, precision = 4, scale = 1)
    private BigDecimal finalAvg;

    @Column(name = "final_target", nullable = false, columnDefinition = "smallint")
    private int finalTarget;

    @Column(name = "rule_version", nullable = false, length = 16)
    private String ruleVersion;

    protected GameMatchEntity() {}

    public GameMatchEntity(String matchType, String roomCode, LocalDateTime startedAt, LocalDateTime endedAt,
                           String winnerRole, String endReason, BigDecimal finalAvg, int finalTarget, String ruleVersion) {
        this.matchType = matchType;
        this.roomCode = roomCode;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.winnerRole = winnerRole;
        this.endReason = endReason;
        this.finalAvg = finalAvg;
        this.finalTarget = finalTarget;
        this.ruleVersion = ruleVersion;
    }

    public Long getId() { return id; }
    public String getMatchType() { return matchType; }
    public String getRoomCode() { return roomCode; }
    public Long getRematchOf() { return rematchOf; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public String getWinnerRole() { return winnerRole; }
    public String getEndReason() { return endReason; }
    public BigDecimal getFinalAvg() { return finalAvg; }
    public int getFinalTarget() { return finalTarget; }
    public String getRuleVersion() { return ruleVersion; }
}
