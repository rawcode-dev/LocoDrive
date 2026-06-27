public class TestResource {
    public static void main(String[] args) {
        System.out.println(TestResource.class.getResourceAsStream("/images/download.png") != null ? "FOUND" : "NOT FOUND");
    }
}
