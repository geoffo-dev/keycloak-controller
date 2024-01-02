package com.kiwigrid.keycloak.controller.realm;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.stream.Collectors;

import com.kiwigrid.keycloak.controller.KubernetesController;
import com.kiwigrid.keycloak.controller.keycloak.KeycloakController;
import com.kiwigrid.keycloak.controller.realm.RealmResource.RealmResourceDoneable;
import com.kiwigrid.keycloak.controller.realm.RealmResource.RealmResourceList;
import com.kiwigrid.keycloak.controller.realm.RealmResource.RealmResourceSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import javax.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;

@Singleton
public class RealmController extends KubernetesController<RealmResource> {

	final KeycloakController keycloak;

	public RealmController(KeycloakController keycloak, KubernetesClient kubernetes) {
		super(kubernetes, RealmResource.DEFINITION, RealmResource.class, RealmResourceList.class,
				RealmResourceDoneable.class);
		this.keycloak = keycloak;
	}

	@Override
	public void apply(RealmResource resource) {
		var spec = resource.getSpec();

		// get keycloak

		var optional = keycloak.get(spec.getKeycloak());
		if (optional.isEmpty()) {
			log.warn("{}/{}: unable to create realm because Keycloak is unknown", spec.getKeycloak(), spec.getRealm());
			updateStatus(resource, "Keycloak " + spec.getKeycloak() + " not found ");
			return;
		}
		var keycloak = optional.get();

		// process realm

		try {

			try {
				keycloak.realm(spec.getRealm()).toRepresentation();
				log.trace("{}/{}: realm already exists", spec.getKeycloak(), spec.getRealm());
			} catch (NotFoundException e) {
				var representation = new RealmRepresentation();
				representation.setRealm(spec.getRealm());
				representation.setEnabled(true);
				keycloak.realms().create(representation);
				log.info("{}/{}: created realm", spec.getKeycloak(), spec.getRealm());
			}

			manageRealmRoles(keycloak, spec);

			updateStatus(resource, null);
		} catch (WebApplicationException e) {
			var response = WebApplicationException.class.cast(e).getResponse();
			var error = "Keycloak returned " + response.getStatus() + " with: " + response.readEntity(String.class);
			log.error(spec.getKeycloak() + "/" + spec.getRealm() + ": " + error);
			updateStatus(resource, error);
		} catch (RuntimeException e) {
			log.error(spec.getKeycloak() + "/" + spec.getRealm() + ": unable to create realm", e);
			updateStatus(resource, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	@Override
	public void delete(RealmResource resource) {
		log.warn("{}/{}: deleting realm not supported!",
				resource.getSpec().getKeycloak(), resource.getSpec().getRealm());
	}

	@Override
	public void retry() {
		customResources.list().getItems().stream()
				.filter(r -> r.getStatus().getError() != null)
				.forEach(this::apply);
	}

	// internal

	void updateStatus(RealmResource resource, String error) {

		// skip if nothing changed

		if (resource.getStatus().getTimestamp() != null && Objects.equals(resource.getStatus().getError(), error)) {
			return;
		}

		// update status

		resource.getStatus().setError(error);
		resource.getStatus().setTimestamp(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
		customResources.withName(resource.getMetadata().getName()).replace(resource);
	}

	void manageRealmRoles(Keycloak keycloak, RealmResourceSpec spec) {

		// no roles to handle?

		if (spec.getRoles().isEmpty()) {
			return;
		}

		// get existing roles from keycloak

		var existingRealmRoles = keycloak.realm(spec.getRealm()).roles().list().stream()
				.filter(r -> !r.getClientRole())
				.map(RoleRepresentation::getName)
				.collect(Collectors.toSet());

		// create roles if missing

		for (var realmRole : spec.getRoles()) {

			if (existingRealmRoles.contains(realmRole)) {
				log.trace("{}/{}: realm role {} already exists", spec.getKeycloak(), spec.getRealm(), realmRole);
				continue;
			}

			var representation = new RoleRepresentation();
			representation.setClientRole(false);
			representation.setName(realmRole);
			keycloak.realm(spec.getRealm()).roles().create(representation);
			log.info("{}/{}: created realm role {}", spec.getKeycloak(), spec.getRealm(), realmRole);
		}
	}
}