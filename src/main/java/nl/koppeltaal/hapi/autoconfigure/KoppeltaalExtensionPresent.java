package nl.koppeltaal.hapi.autoconfigure;

/**
 * Named in hapi.fhir.custom-interceptor-classes. The starter takes the bean that our auto-configuration provides.
 * Without the jar it cannot load the class, and without the auto-configuration it cannot construct one. Either way it
 * refuses to start. It has no hooks; HAPI logs that it registered nothing.
 */
public final class KoppeltaalExtensionPresent {

  KoppeltaalExtensionPresent() {
  }
}
