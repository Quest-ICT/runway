/**
 * Serving tenant pages (page-as-entry). A page is files on disk under a site's {@code templates/}
 * folder: {@code <slug>.html} (a complete HTML document) + {@code <slug>.meta.yml} (its metadata)
 * and an optional {@code <slug>.th.xml} (decoupled-logic, carrying the boilerplate seam-pulls that
 * pull the master's chrome). The page is identified by a <b>stable id (the file name)</b>, decoupled
 * from its public URL.
 *
 * <ul>
 *   <li>{@link mx.quest.runway.page.Page} / {@link mx.quest.runway.page.PageStore} — the page record
 *       and its typed store.</li>
 *   <li>{@link mx.quest.runway.page.PageRouting} — maps a request URL to a {@code (page, locale)}.</li>
 *   <li>{@link mx.quest.runway.page.RenderModelBuilder} — assembles the immutable render model.</li>
 *   <li>{@link mx.quest.runway.page.PageViews} — materializes the SPI
 *       {@link mx.quest.runway.spi.model.PageMeta} from a page.</li>
 * </ul>
 */
package mx.quest.runway.page;
