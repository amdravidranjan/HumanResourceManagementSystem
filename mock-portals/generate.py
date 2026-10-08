"""Generate three simulated job-portal websites for the HRMS web crawler.

Output: mock-portals/site/ (served by the nginx `mock-portals` container on port 9000).
Deterministic (seeded) so evaluation runs are repeatable. All companies are fictitious.
Run:  python mock-portals/generate.py
"""
import html
import pathlib
import random
import shutil

OUT = pathlib.Path(__file__).resolve().parent / "site"
PER_PAGE = 10
random.seed(2026)

TITLES = [
    "Java Developer", "Senior Java Developer", "Python Developer", "Full Stack Engineer", "Frontend Engineer",
    "DevOps Engineer", "Site Reliability Engineer", "QA Engineer", "Data Engineer", "Data Analyst",
    "Machine Learning Engineer", "Cloud Architect", "Mobile App Developer", "Financial Analyst", "Accountant",
    "Tax Consultant", "Sales Executive", "Business Development Manager", "Digital Marketing Specialist",
    "Content Writer", "SEO Analyst", "Product Manager", "Associate Product Manager", "UX Designer", "UI Designer",
    "Customer Support Associate", "Service Desk Analyst", "Legal Counsel", "Compliance Officer",
    "Talent Acquisition Specialist", "HR Generalist", "Operations Executive", "Supply Chain Analyst",
]
COMPANIES = [
    "Kaveri Softworks", "Nilgiri Analytics", "Marina Systems", "Vaigai Fintech", "Thamirabarani Labs",
    "Cauvery Cloud", "Palar Digital", "Mylapore Media", "Adyar Infotech", "Guindy Robotics", "Velachery Ventures",
    "Tambaram Tech", "Koyambedu Logistics", "Egmore Health IT", "Besant Design Studio", "Kalpakkam Energy",
    "Sholinganallur Data", "Perungudi Payments", "Porur Pharma Tech", "Ennore Shipping Systems",
]
CITIES = ["Chennai", "Bengaluru", "Hyderabad", "Pune", "Mumbai", "Delhi", "Kolkata", "Coimbatore", "Kochi", "Noida"]
PORTALS = {
    "portal-a": ("NaukriSim", 45, None),
    "portal-b": ("JobHubSim", 40, "admin"),
    "portal-c": ("TalentBaySim", 35, "private"),
}
OVERLAP = {"portal-b": 10, "portal-c": 8}   # jobs re-posted from earlier portals (crawler must de-duplicate)


def page(title, body):
    return (f"<!doctype html><html><head><meta charset='utf-8'><title>{html.escape(title)}</title>"
            "<style>body{font-family:sans-serif;max-width:760px;margin:2rem auto}"
            ".job-posting{border:1px solid #ccc;padding:1rem;border-radius:8px}</style></head>"
            f"<body>{body}</body></html>")


def make_job(k):
    title = random.choice(TITLES)
    company = random.choice(COMPANIES)
    city = random.choice(CITIES)
    exp = random.randint(0, 10)
    salary = random.randint(4, 40)
    desc = (f"{company} is hiring a {title} in {city}. {exp}+ years of experience. "
            f"CTC up to {salary} LPA. Hybrid work, health insurance and learning budget.")
    return {"title": title, "company": company, "location": city, "description": desc}


def unique_jobs(n, taken):
    jobs = []
    while len(jobs) < n:
        j = make_job(len(jobs))
        key = (j["title"], j["company"], j["location"])
        if key not in taken:
            taken.add(key)
            jobs.append(j)
    return jobs


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    taken = set()
    published = []
    summary = []
    for slug, (name, count, hidden) in PORTALS.items():
        reposts = random.sample(published, OVERLAP.get(slug, 0)) if published else []
        jobs = reposts + unique_jobs(count - len(reposts), taken)
        random.shuffle(jobs)
        published.extend(j for j in jobs if j not in published)
        base = OUT / slug
        pages = (len(jobs) + PER_PAGE - 1) // PER_PAGE
        links = "".join(f"<li><a href='jobs-{p}.html'>Openings page {p}</a></li>" for p in range(1, pages + 1))
        extra = f"<p><a href='{hidden}/index.html'>{hidden} area</a></p>" if hidden else ""
        write(base / "index.html", page(name, f"<h1>{name}</h1><p>Simulated job portal for the HRMS crawler.</p>"
                                              f"<ul>{links}</ul>{extra}<p><a href='https://example.com/'>external link</a></p>"))
        for p in range(1, pages + 1):
            chunk = jobs[(p - 1) * PER_PAGE: p * PER_PAGE]
            items = "".join(
                f"<li><a href='job-{(p - 1) * PER_PAGE + i + 1}.html'>{html.escape(j['title'])} - "
                f"{html.escape(j['company'])}, {html.escape(j['location'])}</a></li>" for i, j in enumerate(chunk))
            nav = (f"<a href='jobs-{p - 1}.html'>prev</a> " if p > 1 else "") + \
                  (f"<a href='jobs-{p + 1}.html'>next</a>" if p < pages else "")
            write(base / f"jobs-{p}.html", page(f"{name} page {p}", f"<h1>{name} - page {p}</h1><ul>{items}</ul>{nav}"
                                                                     "<a href='index.html'>home</a>"))
        for k, j in enumerate(jobs, start=1):
            body = (f"<article class='job-posting'><h1 class='title'>{html.escape(j['title'])}</h1>"
                    f"<p>Company: <span class='company'>{html.escape(j['company'])}</span></p>"
                    f"<p>Location: <span class='location'>{html.escape(j['location'])}</span></p>"
                    f"<div class='description'>{html.escape(j['description'])}</div></article>"
                    f"<p><a href='jobs-{(k - 1) // PER_PAGE + 1}.html'>back to listings</a></p>")
            write(base / f"job-{k}.html", page(j["title"], body))
        if hidden:
            write(base / hidden / "index.html", page("restricted", "<article class='job-posting'><h1 class='title'>"
                                                                    "Restricted Listing</h1><span class='company'>Hidden"
                                                                    "</span><span class='location'>Nowhere</span>"
                                                                    "<div class='description'>robots.txt forbids crawling this page"
                                                                    "</div></article>"))
        summary.append(f"{slug}: {len(jobs)} jobs ({len(reposts)} re-posted), {pages} listing pages")
    write(OUT / "robots.txt", "User-agent: *\nDisallow: /portal-b/admin/\nDisallow: /portal-c/private/\n")
    write(OUT / "index.html", page("Mock portals", "<h1>Simulated job portals</h1><ul>"
                                   + "".join(f"<li><a href='{s}/index.html'>{n}</a></li>" for s, (n, _, _) in PORTALS.items())
                                   + "</ul>"))
    print("\n".join(summary))
    print(f"unique jobs: {len(published)}")


if __name__ == "__main__":
    main()
