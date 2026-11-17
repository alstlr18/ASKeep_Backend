package com.GDGoCSMU.ASKeep.domain.session;

import com.GDGoCSMU.ASKeep.domain.session.entity.Session;
import com.GDGoCSMU.ASKeep.domain.session.entity.SessionStatus;
import com.GDGoCSMU.ASKeep.domain.session.repository.SessionRepository;
import com.GDGoCSMU.ASKeep.global.exception.BusinessException;
import com.GDGoCSMU.ASKeep.global.exception.ErrorCode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SessionAccessService {
    private final SessionRepository sessionRepository;
    private final SessionParticipantRepository participantRepository;

    public Session get(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new EntityNotFoundException("세션을 찾을 수 없습니다."));
    }

    public Session requireHost(Long sessionId, Long userId) {
        Session session = get(sessionId);
        if (!session.isPresenter(userId)) throw new AccessDeniedException("세션 host만 요청할 수 있습니다.");
        return session;
    }

    public Session requireMember(Long sessionId, Long userId) {
        Session session = get(sessionId);
        if (!session.isPresenter(userId) && !participantRepository.existsBySession_IdAndUser_Id(sessionId, userId)) {
            throw new AccessDeniedException("세션 host 또는 참여자만 요청할 수 있습니다.");
        }
        return session;
    }

    /**
     * 아카이브 공개용. 종료된(ENDED) 세션은 로그인한 누구나 통과하고,
     * 아직 끝나지 않은 세션은 host·참여자만 통과한다. (입장 코드 없이 진행 중 세션 자료가 새지 않도록)
     * 자료 목록·원본 다운로드에만 쓴다.
     */
    public Session requireMemberOrArchived(Long sessionId, Long userId) {
        Session session = get(sessionId);
        if (session.getStatus() == SessionStatus.ENDED) return session;
        if (!session.isPresenter(userId) && !participantRepository.existsBySession_IdAndUser_Id(sessionId, userId)) {
            throw new AccessDeniedException("종료되지 않은 세션은 host 또는 참여자만 요청할 수 있습니다.");
        }
        return session;
    }

    /** host 또는 참여자이면서, 세션이 진행 중(ONGOING)일 때만 통과. 질문 등록처럼 진행 중에만 허용되는 동작에 쓴다. */
    public Session requireLiveMember(Long sessionId, Long userId) {
        Session session = requireMember(sessionId, userId);
        if (!session.isLive()) throw new BusinessException(ErrorCode.SESSION_NOT_IN_PROGRESS);
        return session;
    }
}
