/**
 * The database plugin's published interface: what a <b>consumer</b> plugin uses to keep its own data in the one
 * database a Runway installation is configured with, and what a <b>provider</b> plugin implements to add a
 * database product.
 *
 * <h2>For a consumer</h2>
 * <ol>
 *   <li>Declare {@code requires: [ "database >= <the lowest version whose types you use>" ]} in {@code plugin.yml}
 *       and compile this package as {@code provided}: at runtime the shared plugin class loader supplies it.</li>
 *   <li>Register one {@link mx.quest.database.spi.DatabaseConsumer} bean. Ship your migration scripts under
 *       {@code db/<your-plugin-id>/<dialect>/<app|site>/}, with an {@code index} file listing them in order.</li>
 *   <li>Inject {@link mx.quest.database.spi.Databases} and call {@code of(yourConsumerBean)} for your own
 *       {@link mx.quest.database.spi.PluginDatabase}. Everything you do goes through its
 *       {@link mx.quest.database.spi.Scope scopes}: one per site, plus one for data that belongs to no site.</li>
 * </ol>
 * Never call it from a constructor, a {@code @Bean} method, {@code @PostConstruct} or
 * {@code PluginLifecycle.started()}: the database may not be reachable yet, and Runway starts without it.
 *
 * <h2>For a provider</h2>
 * Implement {@link mx.quest.database.spi.DatabaseProvider} in a plugin that {@code requires: database} and ships its
 * driver in {@code lib/}. The operator picks the provider by its {@link mx.quest.database.spi.DatabaseProvider#id()}
 * in the database plugin's settings.
 *
 * <h2>Stability</h2>
 * This package evolves additively within a major version of the database plugin: interfaces gain {@code default}
 * methods, records keep their old constructors. A breaking change ships under a new package or a new plugin id.
 */
package mx.quest.database.spi;
