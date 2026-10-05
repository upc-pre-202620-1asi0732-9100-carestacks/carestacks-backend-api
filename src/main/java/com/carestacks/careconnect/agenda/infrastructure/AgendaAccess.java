package com.carestacks.careconnect.agenda.infrastructure;

import com.carestacks.careconnect.agenda.application.abstractions.AgendaService;
import com.carestacks.careconnect.agenda.application.agenda.dtos.HealthEventDto;
import com.carestacks.careconnect.agenda.application.agenda.requests.CreateHealthEventRequest;
import com.carestacks.careconnect.consents.application.abstractions.ConsentManagementService;
import com.carestacks.careconnect.consents.domain.consents.enums.ConsentView;
import com.carestacks.careconnect.iam.application.abstractions.AuthService;
import com.carestacks.careconnect.iam.application.iam.dtos.SessionValidationDto;
import com.carestacks.careconnect.iam.domain.iam.enums.UserRole;
import com.carestacks.careconnect.shared.domain.exceptions.BusinessRuleException;
import com.carestacks.careconnect.shared.domain.exceptions.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Checks current sessions and consent on each Agenda request, including mutations. */
@Service
public class AgendaAccess {
    private final AuthService auth;
    private final ConsentManagementService consents;
    private final AgendaService agenda;

    public AgendaAccess(AuthService auth, ConsentManagementService consents, AgendaService agenda) {
        this.auth = auth;
        this.consents = consents;
        this.agenda = agenda;
    }

    public void requirePatient(String header, UUID patientId) {
        requirePatient(requireSession(header), patientId);
    }

    private void requirePatient(SessionValidationDto session, UUID patientId) {
        if (session.role() == UserRole.PATIENT && session.userId().equals(patientId)) return;
        if (session.role() == UserRole.CAREGIVER && consents.canCaregiverView(session.userId(), patientId, ConsentView.AGENDA)) return;
        throw new AccessDeniedException("No tienes permiso para acceder a esta agenda.");
    }

    public void requireCreation(String header, CreateHealthEventRequest request) {
        var session = requireSession(header);
        requirePatient(session, request.getPatientId());
        if (session.role() == UserRole.CAREGIVER) {
            if (request.getCaregiverId() != null && !session.userId().equals(request.getCaregiverId())) {
                throw new AccessDeniedException("El cuidador del evento debe coincidir con la sesión.");
            }
            request.setCaregiverId(session.userId());
        }
    }

    public HealthEventDto requireEvent(String header, UUID eventId) {
        var session = requireSession(header);
        var event = agenda.getById(eventId);
        requirePatient(session, event.patientId());
        return event;
    }

    public UUID visiblePatient(String header) {
        var session = requireSession(header);
        if (session.role() == UserRole.PATIENT) return session.userId();
        var token = header.substring(7);
        try {
            var consent = consents.getMyCaregiverProfile(token);
            requirePatient(session, consent.patientId());
            return consent.patientId();
        } catch (ResourceNotFoundException exception) {
            throw new AccessDeniedException("No tienes permiso para acceder a una agenda.");
        }
    }

    private SessionValidationDto requireSession(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.substring(7).isBlank()) {
            throw new BadCredentialsException("Debes iniciar sesión para acceder a la agenda.");
        }
        try {
            return auth.validateSession(header.substring(7), null);
        } catch (BusinessRuleException | ResourceNotFoundException exception) {
            throw new BadCredentialsException("Tu sesión no está activa. Inicia sesión nuevamente.");
        }
    }
}
