import java.io.*;
import java.security.*;
import java.util.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import org.w3c.dom.*;

public class DigestCalculator {

    public static void main(String[] args) throws Exception {
        // valida quantidade
        if (args.length < 3) {
            System.out.println("Uso: DigestCalculator <Tipo_Digest> <Caminho_da_Pasta_dos_Arquivos> <Caminho_ArqListaDigest>");
            System.out.println("Tipos de digest suportados: MD5, SHA1, SHA256, SHA512");
            return;
        }

        String tipoDigest = args[0].toUpperCase();
        String caminhoPasta = args[1];
        String caminhoArqListaDigest = args[2];

        // valida tipo
        if (!tipoDigest.equals("MD5") && !tipoDigest.equals("SHA1") &&
            !tipoDigest.equals("SHA256") && !tipoDigest.equals("SHA512")) {
            System.out.println("Tipo de digest invalido. Use: MD5, SHA1, SHA256 ou SHA512");
            return;
        }

        // valida caminho
        File pasta = new File(caminhoPasta);
        if (!pasta.exists() || !pasta.isDirectory()) {
            System.out.println("Erro: Pasta não encontrada: " + caminhoPasta);
            return;
        }

        String algoritmo = getAlgoritmo(tipoDigest);

        // lista arquivos diretos
        File[] arquivos = pasta.listFiles(File::isFile);
        if (arquivos == null || arquivos.length == 0) {
            System.out.println("Nenhum arquivo encontrado na pasta: " + caminhoPasta);
            return;
        }

        Arrays.sort(arquivos, Comparator.comparing(File::getName));

        // calcula digest dos arquivos
        Map<String, String> digestsCalculados = new LinkedHashMap<>();
        for (File arq : arquivos) {
            String hexDigest = calcularDigest(arq, algoritmo);
            digestsCalculados.put(arq.getName(), hexDigest);
        }

        // carrega catalogo xml existente ou cria novo
        Map<String, Map<String, String>> catalogoXML = new LinkedHashMap<>();
        File arqListaDigest = new File(caminhoArqListaDigest);
        Document doc = null;

        if (arqListaDigest.exists()) {
            doc = carregarXML(arqListaDigest);
            catalogoXML = parsearCatalogo(doc);
        } else {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            doc = db.newDocument();
            Element catalog = doc.createElement("CATALOG");
            doc.appendChild(catalog);
        }

        // indexa digests calculados para detectar colisao 
        Map<String, List<String>> digestParaNomes = new HashMap<>();
        for (Map.Entry<String, String> entry : digestsCalculados.entrySet()) {
            String hex = entry.getValue();
            digestParaNomes.computeIfAbsent(hex, k -> new ArrayList<>()).add(entry.getKey());
        }

        // indexa digests do xml
        Map<String, List<String>> digestCatalogoParaNomes = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : catalogoXML.entrySet()) {
            String nomeArqCatalogo = entry.getKey();
            Map<String, String> digestsDoArq = entry.getValue();
            if (digestsDoArq.containsKey(tipoDigest)) {
                String hexCatalogo = digestsDoArq.get(tipoDigest).trim().toLowerCase();
                digestCatalogoParaNomes.computeIfAbsent(hexCatalogo, k -> new ArrayList<>()).add(nomeArqCatalogo);
            }
        }

        List<String> notFoundArqs = new ArrayList<>();

        //  calcula status por arquivo
        for (Map.Entry<String, String> entry : digestsCalculados.entrySet()) {
            String nomeArq = entry.getKey();
            String digestHex = entry.getValue();
            String digestHexLower = digestHex.toLowerCase();

            String status;

            // verifica colisao na pasta
            boolean colisaoPasta = false;
            List<String> nomesComMesmoDigest = digestParaNomes.get(digestHex);
            if (nomesComMesmoDigest != null && nomesComMesmoDigest.size() > 1) {
                colisaoPasta = true;
            }

            //  verifica colisao no xml
            boolean colisaoCatalogo = false;
            List<String> nomesCatalogoComMesmoDigest = digestCatalogoParaNomes.get(digestHexLower);
            if (nomesCatalogoComMesmoDigest != null) {
                for (String nomeCat : nomesCatalogoComMesmoDigest) {
                    if (!nomeCat.equals(nomeArq)) {
                        colisaoCatalogo = true;
                        break;
                    }
                }
            }

            if (colisaoPasta || colisaoCatalogo) {
                status = "COLISION";
            } else {
                // comparar com xml
                Map<String, String> digestsNoXML = catalogoXML.get(nomeArq);
                if (digestsNoXML == null || !digestsNoXML.containsKey(tipoDigest)) {
                    status = "NOT FOUND";
                    notFoundArqs.add(nomeArq);
                } else {
                    String digestXML = digestsNoXML.get(tipoDigest).trim().toLowerCase();
                    if (digestXML.equals(digestHexLower)) {
                        status = "OK";
                    } else {
                        status = "NOT OK";
                    }
                }
            }

            System.out.println(nomeArq + " " + tipoDigest + " " + digestHex + " (" + status + ")");
        }

        //persistir not found
        if (!notFoundArqs.isEmpty()) {
            Element catalogElement = doc.getDocumentElement();

            for (String nomeArq : notFoundArqs) {
                String digestHex = digestsCalculados.get(nomeArq);

                // xml: localizar file_entry existente
                NodeList fileEntries = catalogElement.getElementsByTagName("FILE_ENTRY");
                Element fileEntryExistente = null;
                for (int i = 0; i < fileEntries.getLength(); i++) {
                    Element fe = (Element) fileEntries.item(i);
                    NodeList fileNames = fe.getElementsByTagName("FILE_NAME");
                    if (fileNames.getLength() > 0) {
                        String fn = fileNames.item(0).getTextContent().trim();
                        if (fn.equals(nomeArq)) {
                            fileEntryExistente = fe;
                            break;
                        }
                    }
                }

                Element digestEntry = doc.createElement("DIGEST_ENTRY");
                Element digestType = doc.createElement("DIGEST_TYPE");
                digestType.setTextContent(tipoDigest);
                Element digestHexElem = doc.createElement("DIGEST_HEX");
                digestHexElem.setTextContent(digestHex);
                digestEntry.appendChild(digestType);
                digestEntry.appendChild(digestHexElem);

                if (fileEntryExistente != null) {
                    // xml: anexar digest_entry
                    fileEntryExistente.appendChild(digestEntry);
                } else {
                    // xml: criar file_entry
                    Element fileEntry = doc.createElement("FILE_ENTRY");
                    Element fileName = doc.createElement("FILE_NAME");
                    fileName.setTextContent(nomeArq);
                    fileEntry.appendChild(fileName);
                    fileEntry.appendChild(digestEntry);
                    catalogElement.appendChild(fileEntry);
                }
            }

            // xml salva alteracoes
            removerWhitespace(doc);
            salvarXML(doc, arqListaDigest);
        }
    }

    // jca: resolver algoritmo
    private static String getAlgoritmo(String tipoDigest) {
        switch (tipoDigest) {
            case "MD5":    return "MD5";
            case "SHA1":   return "SHA-1";
            case "SHA256": return "SHA-256";
            case "SHA512": return "SHA-512";
            default: throw new IllegalArgumentException("Tipo de digest inválido: " + tipoDigest);
        }
    }

    // digest: calcular por arquivo
    private static String calcularDigest(File arquivo, String algoritmo) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algoritmo);
        try (FileInputStream fis = new FileInputStream(arquivo)) {
            byte[] buffer = new byte[8192];
            int bytesLidos;
            while ((bytesLidos = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesLidos);
            }
        }
        byte[] digestBytes = md.digest();
        return bytesToHex(digestBytes);
    }

    // hex: converter bytes para string
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // xml: carregar documento
    private static Document carregarXML(File arqXML) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(arqXML);
    }

    // parse: mapear catalogo
    private static Map<String, Map<String, String>> parsearCatalogo(Document doc) {
        doc.getDocumentElement().normalize();
        Map<String, Map<String, String>> catalogo = new LinkedHashMap<>();
        NodeList fileEntries = doc.getElementsByTagName("FILE_ENTRY");
        for (int i = 0; i < fileEntries.getLength(); i++) {
            Element fe = (Element) fileEntries.item(i);
            NodeList fileNames = fe.getElementsByTagName("FILE_NAME");
            if (fileNames.getLength() == 0) continue;
            String nomeArq = fileNames.item(0).getTextContent().trim();

            Map<String, String> digestsMap = new LinkedHashMap<>();
            NodeList digestEntries = fe.getElementsByTagName("DIGEST_ENTRY");
            for (int j = 0; j < digestEntries.getLength(); j++) {
                Element de = (Element) digestEntries.item(j);
                NodeList tipos = de.getElementsByTagName("DIGEST_TYPE");
                NodeList hexes = de.getElementsByTagName("DIGEST_HEX");
                if (tipos.getLength() > 0 && hexes.getLength() > 0) {
                    String tipo = tipos.item(0).getTextContent().trim().toUpperCase();
                    String hex  = hexes.item(0).getTextContent().trim().toLowerCase();
                    digestsMap.put(tipo, hex);
                }
            }
            catalogo.put(nomeArq, digestsMap);
        }
        return catalogo;
    }

    private static void removerWhitespace(Node node) {
        NodeList filhos = node.getChildNodes();
        for (int i = filhos.getLength() - 1; i >= 0; i--) {
            Node filho = filhos.item(i);

            if (filho.getNodeType() == Node.TEXT_NODE &&
                filho.getTextContent().trim().isEmpty()) {
                node.removeChild(filho);
            } else if (filho.hasChildNodes()) {
                removerWhitespace(filho);
            }
        }
    }

    // persist: salvar catalogo atualizado

    private static void salvarXML(Document doc, File arqXML) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(arqXML);
        transformer.transform(source, result);
    }
}
