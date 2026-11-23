package com.catchnyang.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/** match_player 한 줄 = 판 하나의 한 역할 */
@Entity
@Table(name = "match_player")
@IdClass(MatchPlayerEntity.Key.class)
public class MatchPlayerEntity {

    public static class Key implements Serializable {
        private Long matchId;
        private String role;

        public Key() {}

        public Key(Long matchId, String role) {
            this.matchId = matchId;
            this.role = role;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(matchId, k.matchId) && Objects.equals(role, k.role);
        }

        @Override
        public int hashCode() {
            return Objects.hash(matchId, role);
        }
    }

    @Id
    @Column(name = "match_id", nullable = false)
    private Long matchId;

    @Id
    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "nickname", nullable = false, length = 20)
    private String nickname;

    /** 역할별 통계 JSON 문자열 (MySQL 이 JSON 으로 검증·저장) */
    @Column(name = "stats", nullable = false, columnDefinition = "json")
    private String stats;

    protected MatchPlayerEntity() {}

    public MatchPlayerEntity(Long matchId, String role, String nickname, String stats) {
        this.matchId = matchId;
        this.role = role;
        this.nickname = nickname;
        this.stats = stats;
    }

    public Long getMatchId() { return matchId; }
    public String getRole() { return role; }
    public String getNickname() { return nickname; }
    public String getStats() { return stats; }
}
