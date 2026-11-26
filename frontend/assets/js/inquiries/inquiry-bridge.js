/**
 * Small classic-script bridge for the shared inquiry service.
 *
 * Page modules import the same singleton directly; the bridge only exposes the
 * approved service surface to public classic scripts and test harnesses.
 */
import inquiryService from "./inquiry-service.js";

window.EVInquiries = inquiryService;
window.dispatchEvent(new CustomEvent("ev-inquiries-ready"));
